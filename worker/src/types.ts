export interface Env {
  USER_ID?: string;
  REQUEST_BOOK_ID?: string;
  DASHSCOPE_API_KEY: string;
  DASHSCOPE_BASE_URL?: string;
  DASHSCOPE_MODEL?: string;
  FEISHU_APP_ID: string;
  FEISHU_APP_SECRET: string;
  FEISHU_OAUTH_APP_ID: string;
  FEISHU_OAUTH_APP_SECRET: string;
  FEISHU_APP_TOKEN: string;
  FEISHU_WIKI_TOKEN?: string;
  FEISHU_TABLE_ID: string;
  PERSONAL_ACCESS_KEY: string;
  FRIEND_TEST_ACCESS_KEY?: string;
  OAUTH_REDIRECT_URL: string;
  TOKEN_ENCRYPTION_KEY: string;
  IDEMPOTENCY: KVNamespace;
  DB: D1Database;
}

export type SemanticKey = "date" | "purpose" | "amount" | "paymentPlatform" | "tags" | "note" | "project";

export interface UserContext {
  id: string;
  openId: string;
  tenantKey: string;
  name: string;
}

export interface UserCredential {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt?: number;
}

export interface FieldMapping {
  semanticKey: SemanticKey;
  fieldId: string;
  displayName: string;
  fieldType: number;
  enabled: boolean;
  options: string[];
}

export interface UserBook {
  id: string;
  userId: string;
  appToken: string;
  tableId: string;
  name: string;
  tableName: string;
  sourceUrl?: string;
  mappings: FieldMapping[];
}

export interface SelectOptions {
  paymentPlatforms: string[];
  tags: string[];
  projects: string[];
  targetUrl?: string;
  bookName?: string;
  tableName?: string;
  fieldNames?: Record<string, string>;
  fields?: PersonalBookField[];
}

export interface PersonalBookField {
  fieldId: string;
  displayName: string;
  fieldType: number;
  primary: boolean;
  options: string[];
  required?: boolean;
  hiddenOptions?: string[];
}

export interface TableTarget {
  appToken?: string;
  wikiToken?: string;
  tableId: string;
  sourceUrl?: string;
}

export interface PersonalFieldMapping {
  semanticKey: SemanticKey;
  fieldId: string;
  displayName: string;
  fieldType: number;
  enabled: boolean;
  options: string[];
}

export interface PersonalBook {
  id: string;
  appToken: string;
  tableId: string;
  bookName: string;
  tableName: string;
  sourceUrl: string;
  target: TableTarget;
  mappings: PersonalFieldMapping[];
  fields?: PersonalBookField[];
  lastUsedAt: number;
}

export interface DynamicDraft {
  supported: boolean;
  values: Record<string, string>;
  confidence: Record<string, number>;
  warnings: string[];
  suggestedOptions: Record<string, string[]>;
}

export interface DynamicRecordInput {
  clientRequestId: string;
  values: Record<string, string>;
}

export interface PersonalBookCatalog {
  currentBookId: string | null;
  books: PersonalBook[];
}

export interface Draft {
  supported: boolean;
  date: string | null;
  purpose: string | null;
  amount: number | null;
  paymentPlatform: string | null;
  tags: string[];
  note: string | null;
  project: string | null;
  confidence: {
    date: number;
    purpose: number;
    amount: number;
    paymentPlatform: number;
    tags: number;
    project: number;
  };
  warnings: string[];
}

export interface RecordInput {
  clientRequestId: string;
  date: string;
  purpose: string;
  amount: number;
  paymentPlatform: string;
  tags: string[];
  note?: string | null;
  project?: string | null;
}
