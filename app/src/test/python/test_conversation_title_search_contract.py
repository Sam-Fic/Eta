"""Guards for title-aware conversation search (Manage Chats 搜索按钮)."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

APP = pathlib.Path(__file__).resolve().parents[3]
SRC = APP / "src/main/kotlin/io/github/mangi/eta"


class ConversationTitleSearchContractTest(unittest.TestCase):
    def test_search_history_matches_titles_and_bodies(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text(encoding="utf-8")
        block = state[state.index("suspend fun searchHistory("):state.index("private fun currentConversationSearchScope()")]
        self.assertIn("searchConversationTitle(", block)
        self.assertIn("searchConversationMessages(", block)
        # 标题命中排在同一会话的正文命中之前。
        self.assertLess(block.index("searchConversationTitle("), block.index("searchConversationMessages("))

    def test_title_hit_has_no_scroll_target(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text(encoding="utf-8")
        block = state[state.index("fun openHistorySearchHit("):state.index("fun consumePendingScrollToMessage()")]
        # 标题命中没有消息 id，不能当成滚动锚点。
        self.assertIn("hit.messageId.takeIf { it.isNotEmpty() }", block)

    def test_search_page_keys_title_hits_separately(self):
        page = (SRC / "ui/screens/chat/MessageSearchScreen.kt").read_text(encoding="utf-8")
        self.assertIn('"title:$conversationId"', page)

    def test_role_label_exists_in_every_locale(self):
        for folder in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            root = ET.parse(APP / "src/main/res" / folder / "strings.xml").getroot()
            names = {element.attrib["name"] for element in root}
            self.assertIn("search_history_role_title", names)


if __name__ == "__main__":
    unittest.main()
