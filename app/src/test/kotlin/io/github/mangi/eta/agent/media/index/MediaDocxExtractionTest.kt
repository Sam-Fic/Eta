package io.github.mangi.eta.agent.media.index

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 文档抽取的纯逻辑单测：DOCX（zip 读 `word/document.xml` → 纯文本）与 PDF 页预算。
 *
 * PDF 页渲染依赖 android.graphics.pdf.PdfRenderer，无法纯 JVM 覆盖；
 * 这里验证可单测的部分：XML→文本、字符截断、zip 条目定位与页数预算常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaDocxExtractionTest {

    @Test
    fun docxTextExtractsTextRunsAndParagraphBreaks() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document $WORD_NAMESPACE>
              <w:body>
                <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段正文</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        // 同段落的多个文本运行直接拼接，段落之间换行。
        assertEquals("Hello world\n第二段正文", MediaExtraction.docxText(xml))
    }

    @Test
    fun docxTextIgnoresMarkupWhitespaceAndNonTextElements() {
        val xml = """
            <w:document $WORD_NAMESPACE>
              <w:body>
                <w:p>
                  <w:pPr><w:jc w:val="center"/></w:pPr>
                  <w:r><w:t>标题</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>单元格</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        // 排版空白与属性值不进正文，只留 w:t 文本与段落换行。
        assertEquals("标题\n单元格", MediaExtraction.docxText(xml))
    }

    @Test
    fun docxTextTruncatesToCharacterBudget() {
        val longText = "字".repeat(MediaExtraction.MAX_DOCUMENT_CHARS + 500)
        // 生产解析器开启命名空间处理，夹具必须声明 w 前缀，否则解析即失败。
        val xml = """
            <w:document $WORD_NAMESPACE>
              <w:body><w:p><w:r><w:t>$longText</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
        assertEquals(MediaExtraction.MAX_DOCUMENT_CHARS, MediaExtraction.docxText(xml).length)
        // 显式小上限同样生效。
        assertEquals(10, MediaExtraction.docxText(xml, limit = 10).length)
    }

    @Test
    fun docxTextReturnsEmptyForDocumentWithoutTextRuns() {
        assertEquals("", MediaExtraction.docxText("<w:document $WORD_NAMESPACE/>"))
    }

    @Test
    fun docxTextFromZipReadsWordDocumentXmlEntry() {
        val xml = """
            <w:document $WORD_NAMESPACE>
              <w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
        val zipBytes = zipOf(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to xml.toByteArray(),
            "word/styles.xml" to "<styles/>".toByteArray(),
        )
        assertEquals("正文", MediaExtraction.docxTextFrom(ByteArrayInputStream(zipBytes)))
    }

    @Test
    fun docxTextFromZipReturnsNullWhenEntryMissingOrContentBroken() {
        // 缺 word/document.xml 的 zip：返回 null（索引侧计 failed，而不是乱码入库）。
        assertNull(
            MediaExtraction.docxTextFrom(
                ByteArrayInputStream(zipOf("word/styles.xml" to "<styles/>".toByteArray())),
            ),
        )
        // 非 zip 内容（如误判扩展名的文本）：解析失败同样返回 null。
        assertNull(MediaExtraction.docxTextFrom(ByteArrayInputStream("plain-text".toByteArray())))
    }

    @Test
    fun pdfPageBudgetCapsPagesFromTheFront() {
        // 页预算：从首页起最多 MAX_PDF_PAGES 页；页数不足时按实际页数。
        assertEquals(MediaExtraction.MAX_PDF_PAGES, MediaExtraction.pdfPageBudget(200))
        assertEquals(3, MediaExtraction.pdfPageBudget(3))
        assertEquals(0, MediaExtraction.pdfPageBudget(0))
        assertTrue(MediaExtraction.MAX_PDF_PAGES <= 8)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** WordprocessingML 命名空间声明；生产侧 [MediaExtraction.docxText] 按命名空间解析，夹具必须声明前缀。 */
        const val WORD_NAMESPACE = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    }
}
