package com.scserver.serverscoreboard;

/**
 * GUI関連の定数を集約したインターフェース
 */
public interface GUIConstants {
    // GUIサイズ
    int GUI_SIZE = 54;          // 6行のチェスト
    int ITEMS_PER_PAGE = 27;    // 1ページあたりのアイテム数

    // 上部ナビゲーションスロット（行0）
    int SLOT_SWITCH = 0;        // ページ切り替えボタン
    int SLOT_RESET = 4;         // リセットボタン
    int SLOT_CLOSE = 8;         // 閉じるボタン

    // ページナビゲーションスロット（行2）
    int SLOT_PREV = 18;         // 前のページ
    int SLOT_NEXT = 26;         // 次のページ

    // アイテム表示範囲（行3-5）
    int SLOT_ITEMS_START = 27;  // アイテム表示開始スロット
    int SLOT_ITEMS_END = 53;    // アイテム表示終了スロット
}
