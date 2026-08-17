package com.maxrave.common

import java.util.Locale

actual fun getDeviceCountry(): String? = Locale.getDefault().country.ifEmpty { null }

actual fun getDeviceLanguage(): String? = Locale.getDefault().language.ifEmpty { null }
