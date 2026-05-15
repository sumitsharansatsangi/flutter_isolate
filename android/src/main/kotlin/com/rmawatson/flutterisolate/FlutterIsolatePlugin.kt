package com.rmawatson.flutterisolate

import android.content.Context
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.lang.reflect.InvocationTargetException
import java.util.LinkedList
import java.util.Queue

internal class IsolateHolder {
    var engine: FlutterEngine? = null
    var isolateId: String? = null

    var startupChannel: EventChannel? = null
    var controlChannel: MethodChannel? = null

    var entryPoint: Long? = null
    var result: MethodChannel.Result? = null
}

class FlutterIsolatePlugin : FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    private lateinit var queuedIsolates: Queue<IsolateHolder>
    private lateinit var activeIsolates: MutableMap<String, IsolateHolder>
    private lateinit var context: Context
    private lateinit var engineGroup: FlutterEngineGroup

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        engineGroup = FlutterEngineGroup(binding.applicationContext)
        setupChannel(binding.binaryMessenger, binding.applicationContext)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    }

    private fun setupChannel(messenger: BinaryMessenger, context: Context) {
        this.context = context
        val controlChannel = MethodChannel(messenger, "$NAMESPACE/control")
        queuedIsolates = LinkedList()
        activeIsolates = HashMap()

        controlChannel.setMethodCallHandler(this)
    }

    private fun startNextIsolate() {
        val isolate = queuedIsolates.peek()

        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, null)
        if (isolate != null) {
            val cbInfo = FlutterCallbackInformation.lookupCallbackInformation(isolate.entryPoint!!)

            isolate.engine = engineGroup.createAndRunEngine(
                context,
                DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    cbInfo.callbackLibraryPath,
                    cbInfo.callbackName,
                ),
            )

            val binaryMessenger = isolate.engine!!.dartExecutor.binaryMessenger
            isolate.controlChannel = MethodChannel(binaryMessenger, "$NAMESPACE/control")
            isolate.startupChannel = EventChannel(binaryMessenger, "$NAMESPACE/event")

            isolate.startupChannel!!.setStreamHandler(this)
            isolate.controlChannel!!.setMethodCallHandler(this)

            if (registrant != null) {
                registerWithCustomRegistrant(isolate.engine!!)
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        if (queuedIsolates.isNotEmpty()) {
            val isolate = queuedIsolates.remove()
            val isolateId = isolate.isolateId!!

            events.success(isolateId)
            events.endOfStream()
            activeIsolates[isolateId] = isolate

            isolate.result!!.success(null)
            isolate.startupChannel = null
            isolate.result = null
        }

        if (queuedIsolates.isNotEmpty()) {
            startNextIsolate()
        }
    }

    override fun onCancel(arguments: Any?) {
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "spawn_isolate" -> {
                val isolate = IsolateHolder()
                val entryPoint = call.argument<Any>("entry_point")
                if (entryPoint is Long) {
                    isolate.entryPoint = entryPoint
                }

                if (entryPoint is Int) {
                    isolate.entryPoint = entryPoint.toLong()
                }
                isolate.isolateId = call.argument("isolate_id")
                isolate.result = result

                queuedIsolates.add(isolate)

                if (queuedIsolates.size == 1) {
                    startNextIsolate()
                }
            }

            "kill_isolate" -> {
                val isolateId = call.argument<String>("isolate_id")

                try {
                    activeIsolates[isolateId]!!.engine!!.destroy()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                activeIsolates.remove(isolateId)
                result.success(true)
            }

            "get_isolate_list" -> {
                val outputList = ArrayList(activeIsolates.keys)

                result.success(outputList)
            }

            "kill_all_isolates" -> {
                val runningIsolates = activeIsolates.values

                for (holder in runningIsolates) {
                    holder.engine!!.destroy()
                }

                queuedIsolates.clear()
                activeIsolates.clear()
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    companion object {
        const val NAMESPACE = "com.rmawatson.flutterisolate"
        private var registrant: Class<*>? = null

        private fun registerWithCustomRegistrant(flutterEngine: FlutterEngine) {
            val customRegistrant = registrant ?: return
            try {
                customRegistrant.getMethod("registerWith", FlutterEngine::class.java).invoke(null, flutterEngine)
                Log.i("FlutterIsolate", "Using custom Flutter plugin registrant ${customRegistrant.canonicalName}")
            } catch (noSuchMethodException: NoSuchMethodException) {
                val error = noSuchMethodException.javaClass.simpleName +
                    ": " + noSuchMethodException.message + "\n" +
                    "The plugin registrant must provide a static registerWith(FlutterEngine) method"
                Log.e("FlutterIsolate", error)
            } catch (invocationException: InvocationTargetException) {
                val target = invocationException.targetException
                val error = target.javaClass.simpleName + ": " + target.message + "\n" +
                    "It is possible the default GeneratedPluginRegistrant is attempting to register\n" +
                    "a plugin that uses registrar.activity() or a similar method. Flutter Isolates have no\n" +
                    "access to the activity() from the registrant. If the activity is being use to register\n" +
                    "a method or event channel, have the plugin use registrar.context() instead. Alternatively\n" +
                    "use a custom registrant for isolates, that only registers plugins that the isolate needs\n" +
                    "to use."
                Log.e("FlutterIsolate", error)
            } catch (except: Exception) {
                Log.e("FlutterIsolate", "${except.javaClass.simpleName} ${except.message}")
            }
        }

        /*
         * This should be used to provide a custom plugin registrant for any FlutterIsolates that are spawned,
         * by copying the GeneratedPluginRegistrant provided by flutter call, say "IsolatePluginRegistrant",
         * modifying the list of plugins that are registered (removing the ones you do not want to use from within
         * a plugin) and passing the class to setCustomIsolateRegistrant in your MainActivity.
         *
         * FlutterIsolatePlugin.setCustomIsolateRegistrant(IsolatePluginRegistrant.class);
         *
         * The list will have to be manually maintained if plugins are added or removed, as Flutter automatically
         * regenerates GeneratedPluginRegistrant.
         */
        @JvmStatic
        fun setCustomIsolateRegistrant(registrant: Class<*>) {
            this.registrant = registrant
        }
    }
}
