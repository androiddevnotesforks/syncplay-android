package app.home.settings

class SettingCreationException(val string: String) : Exception() {

    override fun getLocalizedMessage(): String {
        return string
    }
}