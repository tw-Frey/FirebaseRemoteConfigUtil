package ${PACKAGE_NAME}

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.ktx.Firebase
import com.google.firebase.ktx.app
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig.*
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import com.google.firebase.remoteconfig.internal.ConfigFetchHandler.DEFAULT_MINIMUM_FETCH_INTERVAL_IN_SECONDS
import com.google.firebase.remoteconfig.ktx.get
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.jetbrains.anko.*

/**
 * Firebase Remote Config 抽象類: 讀取 FRC 通用化, 資料封裝成物件
 *
 * 現在僅支援: 數字([Number]), 布林值([Boolean]), 字串([String])
 *
 * PS: 目前是委由 GSON 去反射 field/value, 所以 proguard 時要把 資料物件 keep 起來
 *
 * @param iFirebaseApp 取得 [FirebaseApp][com.google.firebase.FirebaseApp] 接口([IFirebaseApp]) (供客製化及測試使用)
 * @param firebaseRemoteConfigSettings Firebase Remote Config 設定([FirebaseRemoteConfigSettings]) (供客製化及測試使用)
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FirebaseRemoteConfigUtil(
    private val DEBUG: Boolean = false,
    override val iFirebaseApp: IFirebaseApp = IFirebaseApp {
        runCatching(Firebase::app).onFailure(ankoLogger::warn).getOrNull()
    },
    override val firebaseRemoteConfigSettings: FirebaseRemoteConfigSettings = remoteConfigSettings {
        minimumFetchIntervalInSeconds = if (DEBUG) 0 else DEFAULT_MINIMUM_FETCH_INTERVAL_IN_SECONDS
    }
) : IFirebaseRemoteConfigUtil, AnkoLogger by ankoLogger {

    companion object {
        val GSON = Gson()
        /**
         * 用來將 [TypeToken][com.google.gson.reflect.TypeToken] 緩存起來
         */
        val TypeTokenCachedMap = mutableMapOf<Class<*>, TypeToken<*>>()
        /**
         * FirebaseRemoteConfigUtil AnkoLogger 委任者
         */
        private val ankoLogger = AnkoLogger<FirebaseRemoteConfigUtil>()
    }

    /**
     * Do not place Android context classes in static fields
     *
     * (static reference to FirebaseRemoteConfig which has field context pointing to Context);
     *
     * 不要儲存 FirebaseRemoteConfig, 需要時用 getter 取得
     *
     * 也就是說, **getter MUST, getter MUST, getter MUST** (很重要,說三次)
     */
    val firebaseRemoteConfigInstance: FirebaseRemoteConfig? get() =
        iFirebaseApp.get()?.runCatching(Firebase::remoteConfig)?.onFailure(::warn)?.getOrNull()

    /**
     * 取得 [Gson TypeAdapter][com.google.gson.TypeAdapter]
     */
    inline fun <reified T> getAdapter(): TypeAdapter<T>? {
        @Suppress("UNCHECKED_CAST")
        val map = TypeTokenCachedMap as MutableMap<Class<T>, TypeToken<T>> // 因為泛型可抹性, 只好後置強制cast
        val typeToken = map.getOrPut(T::class.java) {
            object : TypeToken<T>() {}
        }
        return typeToken.runCatching(GSON::getAdapter).getOrNull() // Gson 內部有作 TypeAdapter 緩存機制
    }

    /**
     * 從 [data]:T 轉換成 Map<String, JsonPrimitive> (使用 Gson TypeAdapter)
     */
    inline fun <reified T> getMapFromData(data: T): Map<String, JsonPrimitive> {
        val map = mutableMapOf<String, JsonPrimitive>()
        val adapter = getAdapter<T>() ?: return map
        val jsonObject = data.runCatching(adapter::toJsonTree).getOrNull()?.runCatching(JsonElement::getAsJsonObject)?.getOrNull() ?: return map
        for ((name, jsonElement) in jsonObject.entrySet()) {
            val jsonPrimitive = jsonElement.runCatching(JsonElement::getAsJsonPrimitive).getOrNull() ?: continue
            when {
                jsonPrimitive.isNumber  -> map[name] = jsonPrimitive
                jsonPrimitive.isBoolean -> map[name] = jsonPrimitive
                jsonPrimitive.isString  -> map[name] = jsonPrimitive
            }
        }
        return map
    }

    /**
     * 從 [map]:Map<String, JsonPrimitive> 轉換成 data:T (使用 Gson TypeAdapter)
     */
    inline fun <reified T> getDataFromMap(map: Map<String, JsonPrimitive>) : T? {
        val adapter = getAdapter<T>() ?: return null
        val jsonObject = JsonObject()
        for ((name, jsonPrimitive) in map) {
            jsonObject.add(name, jsonPrimitive)
        }
        return jsonObject.runCatching(adapter::fromJsonTree).onFailure(::warn).getOrNull()
    }

    /**
     * 請求 Firebase Remote Config
     *
     * *Non-Null* FirebaseRemoteConfig 依然會去 fetch
     *
     * @sample
     */
    inline fun <reified T> fetchRemoteConfigData(): Flow<Result<T>> = callbackFlow {
        // check Firebase Remote Config App (防呆)
        val mFirebaseRemoteConfig = firebaseRemoteConfigInstance ?: run {
            val result = Result.failure<T>(Throwable("Firebase Remote Config App 還沒準備好"))
            offer(result)
            close()
            return@callbackFlow
        }
        val defaultData: T = T::class.java.newInstance()
        val mapFromData = getMapFromData(defaultData)
        with(mFirebaseRemoteConfig) {
            Tasks.call {
                /**
                 * 檢視用 (可註解block起來)
                 */
                when (val status = info.lastFetchStatus) {
                    LAST_FETCH_STATUS_SUCCESS -> "LAST_FETCH_STATUS_SUCCESS"
                    LAST_FETCH_STATUS_NO_FETCH_YET -> "LAST_FETCH_STATUS_NO_FETCH_YET"
                    LAST_FETCH_STATUS_FAILURE -> "LAST_FETCH_STATUS_FAILURE"
                    LAST_FETCH_STATUS_THROTTLED -> "LAST_FETCH_STATUS_THROTTLED"
                    else -> throw Exception("lastFetchStatus = $status")  // 會由 OnFailureListener 處理
                }.apply(::debug)
            }
            .onSuccessTask {
                setConfigSettingsAsync(firebaseRemoteConfigSettings)
            }
            .onSuccessTask {
                setDefaultsAsync(mapFromData)
            }
            .onSuccessTask {
                fetchAndActivate()
            }
            .addOnSuccessListener {
                verbose("from remote: $it")
                val mapToMutate = mutableMapOf<String, JsonPrimitive>().apply{ putAll(mapFromData) }
                for ((name, jsonPrimitive) in mapFromData) {
                    mapToMutate[name] = when {
                        jsonPrimitive.isNumber  -> this[name].valueSourceLogging(name).runCatching(FirebaseRemoteConfigValue::asDouble).getOrNull()?.run(::JsonPrimitive) ?: continue
                        jsonPrimitive.isBoolean -> this[name].valueSourceLogging(name).runCatching(FirebaseRemoteConfigValue::asBoolean).getOrNull()?.run(::JsonPrimitive) ?: continue
                        jsonPrimitive.isString  -> this[name].valueSourceLogging(name).runCatching(FirebaseRemoteConfigValue::asString).getOrNull()?.run(::JsonPrimitive) ?: continue
                        else -> jsonPrimitive
                    }
                }
                val result = runCatching { getDataFromMap(mapToMutate) ?: defaultData }.onFailure(::warn).getOrDefault(defaultData).run(Result.Companion::success)
                offer(result)
                close()
            }
            .addOnFailureListener {
                offer(Result.failure(it))
                close()
            }
        }
        awaitClose()
    }

    /**
     * 檢視用
     */
    fun FirebaseRemoteConfigValue.valueSourceLogging(name: String) = apply {
        val s = asString()
        "$name = $s " + when (val valueSource = source) {
            VALUE_SOURCE_STATIC -> "(VALUE_SOURCE_STATIC)"
            VALUE_SOURCE_DEFAULT -> "(VALUE_SOURCE_DEFAULT)"
            VALUE_SOURCE_REMOTE -> "(VALUE_SOURCE_REMOTE)"
            else -> "($valueSource)"
        }.apply(::info)
    }
}

/**
 * 規範 FirebaseRemoteConfigUtil 必有 [iFirebaseApp] 和 [firebaseRemoteConfigSettings]
 */
interface IFirebaseRemoteConfigUtil {
    val iFirebaseApp: IFirebaseApp
    val firebaseRemoteConfigSettings: FirebaseRemoteConfigSettings
}

/**
 * 取得 [FirebaseApp][com.google.firebase.FirebaseApp] 接口 (供外部注入指定 FirebaseApp 用的窗口)
 *
 * 有可能第一次取時失敗, 所以用類似 getter 方式去取得 [FirebaseApp][com.google.firebase.FirebaseApp]
 *
 * PS: [FirebaseApp][com.google.firebase.FirebaseApp] 是 singleton
 */
fun interface IFirebaseApp {
    fun get(): FirebaseApp?
}
