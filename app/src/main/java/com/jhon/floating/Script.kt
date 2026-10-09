
package com.jhon.floating

import java.util.UUID

data class Script(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var code: String,
    var enabled: Boolean = true
)
