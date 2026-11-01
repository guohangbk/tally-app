package io.github.guohangbk.tally.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@PublishedApi
internal class ProducerFactory(private val producer: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = producer() as T
}

/**
 * 不用 DI 框架时的最小 ViewModel 入口：`val vm = tallyViewModel { LedgerViewModel(graph) }`。
 * 同一目的地要按参数各持一份状态时传 `key`（例如按 billId 区分的账单详情）。
 */
@Composable
inline fun <reified VM : ViewModel> tallyViewModel(
    key: String? = null,
    noinline producer: () -> VM,
): VM = viewModel(key = key, factory = ProducerFactory(producer))

/**
 * 指定宿主取同一份状态：图标库页要往账本页的弹窗草稿里写回图标，
 * 两页必须共用账本页那个 ViewModelStoreOwner 上的实例。
 */
@Composable
inline fun <reified VM : ViewModel> tallyViewModel(
    owner: ViewModelStoreOwner,
    key: String? = null,
    noinline producer: () -> VM,
): VM = viewModel(viewModelStoreOwner = owner, key = key, factory = ProducerFactory(producer))

