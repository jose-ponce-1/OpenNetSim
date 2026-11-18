package com.opennetsim.ui

import com.opennetsim.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiTextTest {

    @Test
    fun equalTextsCompareByResourceAndArguments() {
        assertEquals(
            UiText.res(R.string.message_profile_saved, UiText.Raw("Metro")),
            UiText.Res(R.string.message_profile_saved, listOf(UiText.Raw("Metro")))
        )
        assertNotEquals(
            UiText.res(R.string.message_profile_saved, UiText.Raw("Metro")),
            UiText.res(R.string.message_profile_deleted, UiText.Raw("Metro"))
        )
    }

    @Test
    fun nestedArgumentsKeepTheirIdentity() {
        val name = UiText.res(R.string.preset_slow_3g_name)
        val message = UiText.res(R.string.message_values_restored, name) as UiText.Res
        assertEquals(listOf(name), message.args)
    }

    @Test
    fun pluralShortcutPassesTheCountAsArgument() {
        assertEquals(
            UiText.Plural(R.plurals.apply_note_invalid, 3, listOf(3)),
            UiText.plural(R.plurals.apply_note_invalid, 3)
        )
    }
}
