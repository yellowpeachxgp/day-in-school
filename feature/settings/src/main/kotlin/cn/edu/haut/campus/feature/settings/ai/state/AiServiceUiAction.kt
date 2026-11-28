package cn.edu.haut.campus.feature.settings.ai.state

/** AI 服务用户动作面；Key 只在内存 CharArray 中传入，ViewModel 必须立即复制并清零入参。 */
interface AiServiceUiAction {
    fun openEditor()
    fun closeEditor()
    fun saveConfiguration(baseUrl: String, model: String, supportsVision: Boolean, key: CharArray)
    fun setEnabled(enabled: Boolean)
    fun openClearConfirmation()
    fun closeClearConfirmation()
    fun clearConfiguration()
    fun startConnectivityTest()
    fun cancelConnectivityTest()
    fun confirmConnectivitySend()
}
