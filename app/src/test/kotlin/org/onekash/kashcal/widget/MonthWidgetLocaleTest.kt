package org.onekash.kashcal.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthWidgetLocaleTest {
    @Test
    fun `every supported resource locale translates the whole week picker`() {
        val res = File("src/main/res")
        val folders = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
        assertTrue(folders.size >= 68)
        for (folder in folders) {
            val file = File(folder, "month_widget_weeks.xml")
            assertTrue("Missing picker translations in ${folder.name}", file.exists())
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            val names = (0 until strings.length).map { strings.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
            assertEquals(setOf("settings_month_widget_weeks", "month_widget_weeks_auto", "month_widget_weeks_description"), names)
            val plurals = doc.getElementsByTagName("item")
            val quantities = (0 until plurals.length).map { index ->
                val item = plurals.item(index)
                assertTrue("Lost numeric format in ${folder.name}", item.textContent.contains("%1\$d"))
                item.attributes.getNamedItem("quantity").nodeValue
            }
            assertTrue("Missing plural fallback in ${folder.name}", "other" in quantities)
        }
    }
}
