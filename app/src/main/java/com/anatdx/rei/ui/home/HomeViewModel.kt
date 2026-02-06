package com.anatdx.rei.ui.home

import android.os.Build
import androidx.lifecycle.ViewModel
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.reid.ReidInstallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首页缓存：切回首页时直接展示，仅在下拉或首次无缓存时加载 */
data class HomeSystemStatus(
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val kernel: String = "",
    val selinux: String = "",
    val ksu: String = "",
    val kpReady: Boolean = false,
    val kpVersion: String = "",
)

data class HomeMurasakiStatus(
    val isConnected: Boolean = false,
    val serviceVersion: Int = -1,
    val ksuVersion: Int = -1,
    val privilegeLevel: Int = -1,
    val privilegeLevelName: String = "Unknown",
    val isKernelModeAvailable: Boolean = false,
    val selinuxContext: String? = null,
    val error: String? = null,
)

data class HomeHymoFsStatus(
    val isAvailable: Boolean = false,
    val stealthEnabled: Boolean = false,
    val hideRulesCount: Int = 0,
    val redirectRulesCount: Int = 0,
)

class HomeViewModel : ViewModel() {
    private val _systemStatus = MutableStateFlow<HomeSystemStatus?>(null)
    val systemStatus: StateFlow<HomeSystemStatus?> = _systemStatus.asStateFlow()

    private val _murasakiStatus = MutableStateFlow<HomeMurasakiStatus?>(null)
    val murasakiStatus: StateFlow<HomeMurasakiStatus?> = _murasakiStatus.asStateFlow()

    private val _hymoFsStatus = MutableStateFlow<HomeHymoFsStatus?>(null)
    val hymoFsStatus: StateFlow<HomeHymoFsStatus?> = _hymoFsStatus.asStateFlow()

    private val _installStatus = MutableStateFlow<ReidInstallStatus>(ReidInstallStatus.Unknown)
    val installStatus: StateFlow<ReidInstallStatus> = _installStatus.asStateFlow()

    private val _moduleMeta = MutableStateFlow<Pair<String?, String?>?>(null)
    val moduleMeta: StateFlow<Pair<String?, String?>?> = _moduleMeta.asStateFlow()

    private val _rootImpl = MutableStateFlow(ReiApplication.rootImplementation)
    val rootImpl: StateFlow<String> = _rootImpl.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()

    fun setSystemStatus(s: HomeSystemStatus) { _systemStatus.value = s }
    fun setMurasakiStatus(s: HomeMurasakiStatus) { _murasakiStatus.value = s }
    fun setHymoFsStatus(s: HomeHymoFsStatus) { _hymoFsStatus.value = s }
    fun setInstallStatus(s: ReidInstallStatus) { _installStatus.value = s }
    fun setModuleMeta(p: Pair<String?, String?>) { _moduleMeta.value = p }
    fun setRootImpl(s: String) { _rootImpl.value = s }

    /** 下拉刷新：清空缓存并递增 refreshTrigger，各卡片会重新加载 */
    fun clearCacheAndBumpRefresh() {
        _systemStatus.value = null
        _murasakiStatus.value = null
        _hymoFsStatus.value = null
        _moduleMeta.value = null
        _refreshTrigger.value = _refreshTrigger.value + 1
    }
}
