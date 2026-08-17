package com.maxrave.common

import platform.Foundation.NSLocale

actual fun getDeviceCountry(): String? = NSLocale.currentLocale.countryCode

actual fun getDeviceLanguage(): String? = NSLocale.currentLocale.languageCode
