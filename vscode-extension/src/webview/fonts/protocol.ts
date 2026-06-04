// Wire protocol between the font-browser host panel and its webview.
// Shared by `fontBrowserPanel.ts` (host) and `main.ts` (webview) so the
// two ends can't drift.

import type { FontCatalog } from "../../googleFontsCatalog";
import type { DownloadedFontView } from "./fontBrowserLogic";

export type HostToWebview =
    | { command: "catalog"; catalog: FontCatalog }
    | { command: "catalogLoading" }
    | { command: "catalogError"; message: string }
    | { command: "downloaded"; fonts: DownloadedFontView[] }
    | {
          command: "downloadState";
          familyId: string;
          family: string;
          state: "downloading" | "done" | "error";
          message?: string;
      };

export type WebviewToHost =
    | { command: "ready" }
    | { command: "refreshCatalog" }
    | { command: "download"; family: string }
    | { command: "removeDownloaded"; familyId: string }
    | { command: "copySnippet"; text: string }
    | { command: "openExternal"; url: string };
