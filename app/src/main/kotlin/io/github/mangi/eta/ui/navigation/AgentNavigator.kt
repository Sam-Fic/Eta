package io.github.mangi.eta.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlin.reflect.KClass

/**
 * AppRoute 的具体类型集合。NavHost 是按具体类型（而不是 sealed 基类）注册目标的，
 * 所以把返回栈条目还原成路由时必须用具体类型的序列化器。
 * 这里读 JVM 的 PermittedSubclasses 而不是 Kotlin 的 sealedSubclasses：
 * 后者依赖 kotlin-reflect，本工程没有引入。新增路由时不需要同步维护这张表。
 */
private val appRouteTypes: List<KClass<out AppRoute>> =
    AppRoute::class.java.permittedSubclasses.orEmpty().map { it.kotlin as KClass<out AppRoute> }

/** 模式串（destination.route）→ 路由类型；导航目标是复用的，所以只需解析一次。 */
private val routeTypesByPattern = mutableMapOf<String, KClass<out AppRoute>?>()

/** 把返回栈条目还原成 [AppRoute]；不是 App 路由（例如导航图本身）时返回 null。 */
internal fun NavBackStackEntry.toAppRoute(): AppRoute? {
    val type = routeTypesByPattern.getOrPut(destination.route.orEmpty()) {
        appRouteTypes.firstOrNull { destination.hasRoute(it) }
    } ?: return null
    return toRoute(type)
}

/**
 * 用 androidx [NavController] 实现的路由栈，公开接口与之前的 miuix 返回栈保持一致。
 */
class AgentNavigator(
    private val navController: NavController,
) {
    fun push(route: AppRoute) {
        if (routes().none { it == route }) {
            navController.navigate(route)
        }
    }

    fun replace(route: AppRoute) {
        val entries = navController.currentBackStack.value
        val existing = entries.indexOfFirst { it.toAppRoute() == route }
        if (existing >= 0) {
            while (entries.size > existing + 1) {
                if (!navController.popBackStack()) break
            }
            return
        }

        val topEntry = entries.lastOrNull { it.toAppRoute() != null }
        if (topEntry == null) {
            navController.navigate(route)
            return
        }
        // 换掉栈顶：一次导航里弹出旧页再入栈，避免出现中间态。
        navController.navigate(route) {
            popUpTo(topEntry.destination.id) { inclusive = true }
        }
    }

    fun pop(): Boolean {
        if (routes().size <= 1) return false
        return navController.popBackStack()
    }

    fun popToHome() {
        if (routes().firstOrNull() !is AppRoute.Home) {
            // 返回栈根不是 Home（历史栈或异常恢复）：整体重置为只有 Home。
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            return
        }
        while (routes().size > 1) {
            if (!navController.popBackStack()) break
        }
    }

    fun current(): AppRoute? = routes().lastOrNull()

    /** 返回栈里的路由（不含导航图条目）。 */
    private fun routes(): List<AppRoute> = navController.currentBackStack.value.mapNotNull { it.toAppRoute() }
}
