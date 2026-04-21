package org.yapyap

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform