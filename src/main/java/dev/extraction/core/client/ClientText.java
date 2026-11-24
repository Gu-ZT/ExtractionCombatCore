package dev.extraction.core.client;

import java.util.function.BiConsumer;

/** 客户端文本及键位名称，供语言数据生成器调用。 */
public final class ClientText {
    private ClientText() {
    }

    public static void fill(BiConsumer<String, String> sink, boolean english) {
        sink.accept("key.category.extractioncombatcore.general", english ? "Extraction Combat Core" : "搜刮与收纳");
        sink.accept("key.extractioncombatcore.open", english ? "Open field pack" : "打开随身背包");
        sink.accept("key.extractioncombatcore.challenge", english ? "Operate lock" : "操控锁具");
        String[][] entries = {
            {"title", "随身收纳", "Field inventory"},
            {"missing_puzzle_widget", "缺少小游戏界面 %s", "Missing puzzle widget for %s"},
            {"lock_title", "开启容器", "Container access"},
            {"cabinet", "容器", "Container"},
            {"section.main", "主仓", "Main compartment"},
            {"section.side", "侧袋", "Side pocket"},
            {"section.drawer", "抽屉", "Drawer"},
            {"empty_section", "%s · 空", "%s · Empty"},
            {"no_pack", "未选择随身背包", "No field pack"},
            {"select_pack", "点击选择随身背包", "Select a field pack"},
            {"equipped_pack", "随身背包位", "Equipped pack"},
            {"equip_hint", "右键装备或取下背包", "Right-click to exchange equipped packs"},
            {"recover", "右键取回到开放的槽位", "Right-click to recover into available slots"},
            {"hotbar", "快捷栏", "Hotbar"},
            {"inventory", "物品栏", "Inventory"},
            {"equipment", "副手和随身背包", "Offhand and field pack"},
            {"drag_hint", "拖动放置 · 靠近格边转向 · 双击转移", "Drag to place  ·  Edges rotate  ·  Double-click to transfer"},
            {"organizing", "正在整理…", "Rearranging…"},
            {"cancel", "取消整理", "Cancel"},
            {"routes", "选择开启方式", "Choose an access route"},
            {"unavailable", "条件不足", "Requirements unmet"},
            {"leave", "Esc 离开", "Esc to leave"},
            {"answer", "输入答案", "Enter answer"},
            {"submit", "确认", "Submit"},
            {"replay", "重播信号", "Replay signal"},
            {"table", "译码表", "Code chart"},
            {"back", "返回锁面板", "Back to lock"},
            {"wrong", "答案有误，请稍后重试", "Incorrect code. Try again shortly."},
            {"cooldown", "稍候", "Wait"},
            {"password_hint", "按地图线索输入密码，保留前导零。", "Enter the code from the map. Keep leading zeros."},
            {"press_hint", "按 %s 或点击操作按钮", "Press %s or click the control"},
            {"sneak_hint", "按住潜行键并按 %s", "Hold sneak and press %s"},
            {"hold_hint", "持续按住 %s 或下方按钮", "Hold %s or press and hold the control"},
            {"rolling_hint", "绿色字符进入白框时按 %s", "Press %s when the green character enters the frame"},
            {"decode_hint", "光标进入白条时按 %s 可加速", "Press %s in the white zone to speed up decoding"},
            {"trigger", "操作 [%s]", "Operate [%s]"},
            {"lock.hold", "长按开锁", "Hold to unlock"},
            {"lock.password", "密码锁", "Passcode lock"},
            {"lock.audio_morse", "音频摩斯", "Morse receiver"},
            {"lock.pattern_morse", "图案摩斯", "Morse plaque"},
            {"lock.rolling_lock", "滚动字符锁", "Rolling lock"},
            {"lock.accelerated_decode", "加速破译", "Timed decoder"},
            {"lock.click", "交互开锁", "Operate lock"},
            {"lock.interact", "交互开锁", "Operate lock"},
            {"lock.right_click", "交互开锁", "Operate lock"},
            {"lock.sneak_interact", "潜行开锁", "Sneak to unlock"},
            {"lock.sneak", "潜行开锁", "Sneak to unlock"}
        };
        for (String[] entry : entries) {
            sink.accept("gui.extractioncombatcore." + entry[0], entry[english ? 2 : 1]);
        }
        sink.accept("extractioncombatcore.rarity.common", english ? "Common" : "普通");
        sink.accept("extractioncombatcore.rarity.uncommon", english ? "Uncommon" : "少见");
        sink.accept("extractioncombatcore.rarity.rare", english ? "Rare" : "稀有");
        sink.accept("extractioncombatcore.rarity.epic", english ? "Epic" : "史诗");
    }
}
