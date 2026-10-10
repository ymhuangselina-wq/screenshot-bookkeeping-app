import copy
import unittest
from unittest.mock import patch, Mock

import server


class GatewayRegressionTests(unittest.TestCase):
    def request(self, catalog, path, body, book_id, actual_fields=None):
        handler = server.Handler.__new__(server.Handler)
        handler.path = path
        handler.command = "POST"
        handler.headers = {"X-Book-Id": book_id}
        handler.body = lambda: body
        replies = []
        handler.sendj = lambda status, data: replies.append((status, data))
        store = Mock()
        store.get.return_value = None
        def upstream(path, method="GET", payload=None):
            if "/fields?" in path:return {"items": actual_fields or []}
            return {"record": {"record_id": "rec-test"}}
        with patch.object(server, "auth"), patch.object(server, "Oss", return_value=store), patch.object(server, "catalog", return_value=copy.deepcopy(catalog)), patch.object(server, "fs", side_effect=upstream) as feishu:
            handler.run()
        return replies[-1], feishu

    def test_save_uses_explicit_book_not_stale_current_pointer(self):
        books = {"currentBookId": "old", "books": [
            {"id": "old", "fields": [{"fieldId": "old-field", "displayName": "旧用途", "fieldType": 1}]},
            {"id": "new", "appToken": "new-app", "tableId": "new-table", "fields": [
                {"fieldId": "name", "displayName": "名称", "fieldType": 1},
                {"fieldId": "date", "displayName": "记录日期", "fieldType": 5, "required": True}]}]}
        reply, feishu = self.request(books, "/records", {"clientRequestId": "req", "values": {"name": "晚餐", "date": "2026-10-02 20:50:05"}}, "new")
        self.assertEqual(201, reply[0])
        self.assertIn("new-app/tables/new-table/records", feishu.call_args.args[0])
        self.assertIsInstance(feishu.call_args.args[2]["fields"]["记录日期"], int)

    def test_save_unknown_book_fails_before_feishu_write(self):
        reply, feishu = self.request({"currentBookId": None, "books": []}, "/records", {"clientRequestId": "req", "values": {"name": "晚餐"}}, "missing")
        self.assertEqual(404, reply[0])
        feishu.assert_not_called()

    def test_first_option_add_hydrates_missing_catalog_field(self):
        books = {"currentBookId": "book", "books": [{"id": "book", "appToken": "app", "tableId": "table", "fields": []}]}
        field = {"field_id": "choice", "field_name": "场景", "type": 3, "property": {"options": [{"name": "餐饮"}]}}
        reply, _ = self.request(books, "/books/current/fields/choice/options", {"option": "旅行"}, "book", [field])
        self.assertEqual(200, reply[0])
        self.assertEqual(["旅行", "餐饮"], reply[1]["books"][0]["fields"][0]["options"])

    def test_internal_oss_endpoint_is_normalized(self):
        self.assertNotIn("-internal.", server.ENDPOINT)

    def test_select_book_is_local_and_updates_order_and_time(self):
        catalog = {"currentBookId": "a", "books": [{"id": "a"}, {"id": "b"}]}
        result = server.select_book(catalog, "b", 123456)
        self.assertEqual("b", result["currentBookId"])
        self.assertEqual(["b", "a"], [x["id"] for x in result["books"]])
        self.assertEqual(123456, result["books"][0]["lastUsedAt"])

    def test_select_unknown_book_is_404(self):
        with self.assertRaises(server.ApiError) as caught:
            server.select_book({"currentBookId": None, "books": []}, "missing")
        self.assertEqual(404, caught.exception.status)

    def test_normalize_accepts_null_property_fields_and_scalar_confidence(self):
        fields = [{"fieldId": "amount", "displayName": "支出金额", "fieldType": 2, "options": []}]
        result = server.normalize({"supported": True, "values": {"amount": "-20"}, "confidence": .8}, fields, "2026-09-20T12:00:00+08:00")
        self.assertEqual("20.00", result["values"]["amount"])
        self.assertEqual(.8, result["confidence"]["amount"])

    def test_parse_ai_content_accepts_text_block_response(self):
        response = {"choices": [{"message": {"content": [{"type": "text", "text": '{"supported":true,"values":{}}'}]}}]}
        self.assertTrue(server.parse_ai_content(response)["supported"])

    def test_normalize_accepts_exact_unique_name_and_numeric_amount(self):
        fields = [{"fieldId": "f1", "displayName": "金额", "fieldType": 2}]
        result = server.normalize({"supported": True, "values": {"金额": 20}}, fields, None)
        self.assertEqual({"f1": "20.00"}, result["values"])

    def test_normalize_does_not_guess_ambiguous_field_names(self):
        fields = [{"fieldId": key, "displayName": "名称", "fieldType": 1} for key in ["a", "b"]]
        result = server.normalize({"supported": True, "values": {"名称": "test"}}, fields, None)
        self.assertEqual({}, result["values"])

    def test_parse_ai_content_accepts_fenced_json(self):
        response = {"choices": [{"message": {"content": '```json\n{"supported":true,"values":{}}\n```'}}]}
        self.assertTrue(server.parse_ai_content(response)["supported"])

    def test_parse_ai_content_reports_upstream_format_instead_of_internal_error(self):
        with self.assertRaises(server.ApiError) as caught:
            server.parse_ai_content({"choices": []})
        self.assertEqual(502, caught.exception.status)
        self.assertEqual("ai_invalid_response", caught.exception.code)

    def test_public_fields_accepts_null_property(self):
        result = server.public_fields([{"field_id": "f", "field_name": "用途", "type": 1, "property": None}])
        self.assertEqual([], result[0]["options"])

    def test_public_fields_keeps_hidden_options_out_of_app_without_deleting_them(self):
        actual = [{"field_id": "f", "field_name": "场景", "type": 3, "property": {"options": [{"id": "a", "name": "家居"}, {"id": "b", "name": "通勤"}]}}]
        result = server.public_fields(actual, [{"fieldId": "f", "hiddenOptions": ["通勤"]}])
        self.assertEqual(["家居"], result[0]["options"])
        self.assertEqual(["通勤"], result[0]["hiddenOptions"])

    def test_rename_option_preserves_original_id_and_other_options(self):
        actual = {"field_name": "场景", "type": 3, "property": {"options": [{"id": "a", "name": "家居", "color": 1}, {"id": "b", "name": "通勤"}]}}
        payload = server.rename_option_payload(actual, "家居", "日用/家居")
        self.assertEqual({"id": "a", "name": "日用/家居", "color": 1}, payload["property"]["options"][0])
        self.assertEqual({"id": "b", "name": "通勤"}, payload["property"]["options"][1])

    def test_async_catalog_save_updates_cache_before_network_write(self):
        class SlowStore:
            def put(self, key, value):
                raise RuntimeError("simulated slow/unavailable OSS")
        value = {"currentBookId": "b", "books": [{"id": "b"}]}
        server.save_catalog_async(SlowStore(), value)
        self.assertEqual("b", server.CATALOG_CACHE["value"]["currentBookId"])


if __name__ == "__main__":
    unittest.main()
