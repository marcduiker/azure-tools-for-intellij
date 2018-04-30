fun String.toBooleanChecked() = when (this) {
    "true" -> true
    "false" -> false
    else -> error("Unable to parse value '$this' as boolean")
}
