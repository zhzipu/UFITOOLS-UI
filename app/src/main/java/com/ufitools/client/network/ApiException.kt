package com.ufitools.client.network

/** 网络 / 业务异常，message 直接展示给用户 */
class ApiException(message: String, val code: Int = 0) : Exception(message)
