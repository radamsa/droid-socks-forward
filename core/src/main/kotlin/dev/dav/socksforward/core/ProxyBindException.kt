package dev.dav.socksforward.core

/** The listening socket could not be created; [message] is user facing. */
class ProxyBindException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
