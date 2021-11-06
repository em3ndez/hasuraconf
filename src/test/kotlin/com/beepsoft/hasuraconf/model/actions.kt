package com.beepsoft.hasuraconf.model

import com.beepsoft.hasuraconf.annotation.HasuraAction
import com.beepsoft.hasuraconf.annotation.HasuraField
import com.beepsoft.hasuraconf.annotation.HasuraType

// createUserAndCalendar and createUserAndCalendar2 have same input params, but will have differenet input types
// because the params are all primitives
@HasuraAction(
    handler = "{{HANDLER_URL}}"
)
fun createUserAndCalendar(
    name: String,
    description: String
): String {
    TODO()
}

@HasuraAction(
    handler = "{{HANDLER_URL}}"
)
fun createUserAndCalendar2(
    name: String,
    description: String,
): String {
    TODO()
}

// createUserAndCalendar3 and createUserAndCalendar4 will use the same UserAndCalendarInput type but their output
// types will differ
@HasuraAction(
    handler = "{{HANDLER_URL}}"
)
fun createUserAndCalendar3(
    args: UserAndCalendarInput
): String {
    TODO()
}

class ActionTest {
    @HasuraAction(
        handler = "{{HANDLER_URL}}"
    )
    fun createUserAndCalendar4(
        args: UserAndCalendarInput,
    ): UserAndCalendar
    {
        TODO()
    }

    @HasuraAction(
        handler = "{{HANDLER_URL}}",
        outputTypeName = "UserAndCalendarOutput5"
    )
    fun createUserAndCalendar5(
        args: UserAndCalendarInput,
    ): UserAndCalendar
    {
        TODO()
    }

}

@HasuraType("UserAndCalendarOutput")
data class UserAndCalendar(
    var userName: String,
    @HasuraField(type="bigint!!!!!")
    var userId: Long,
    var calendarId: Long
)

data class UserAndCalendarInput(
    val name: String,
    val description: String
)