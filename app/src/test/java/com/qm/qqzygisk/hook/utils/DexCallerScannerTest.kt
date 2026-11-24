package com.qm.qqzygisk.hook.utils

import com.v7878.dex.DexConstants.ACC_PUBLIC
import com.v7878.dex.DexConstants.ACC_STATIC
import com.v7878.dex.DexIO
import com.v7878.dex.DexVersion
import com.v7878.dex.WriteOptions
import com.v7878.dex.builder.ClassBuilder
import com.v7878.dex.builder.CodeBuilder.InvokeKind.INTERFACE
import com.v7878.dex.builder.CodeBuilder.InvokeKind.VIRTUAL
import com.v7878.dex.immutable.Dex
import com.v7878.dex.immutable.MethodId
import com.v7878.dex.immutable.ProtoId
import com.v7878.dex.immutable.TypeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 验证纯 Kotlin DEX 调用方扫描器能准确识别虚拟调用与接口调用（包含多 DEX 压缩包与版本缓存）。
 */
class DexCallerScannerTest {
    private val writeOptions = WriteOptions.defaultOptions()
        .withTargetApi(28)
        .withTargetForArt(true)
        .withDexVersion(DexVersion.DEX039)

    @Test
    fun `scanDex finds virtual and interface callers while ignoring non-callers`() {
        val respHandlerType = TypeId.ofName("mqq.app.msghandle.MsgRespHandler")
        val dispatchMethod = MethodId.of(
            respHandlerType,
            "dispatchRespMsg",
            ProtoId.of(TypeId.V, TypeId.OBJECT),
        )

        val sessionIfaceType = TypeId.ofName("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession")
        val onMsfPushMethod = MethodId.of(
            sessionIfaceType,
            "onMsfPush",
            ProtoId.of(TypeId.V, TypeId.of(String::class.java), TypeId.of(ByteArray::class.java)),
        )

        val unrelatedMethod = MethodId.of(
            TypeId.OBJECT,
            "toString",
            ProtoId.of(TypeId.of(String::class.java)),
        )

        val dispatcherClassId = TypeId.ofName("com.example.FakeDispatcher")
        val dispatcherDef = ClassBuilder.build(dispatcherClassId) { cb ->
            cb.withSuperClass(TypeId.OBJECT)
                .withFlags(ACC_PUBLIC)
                .withMethod { mb ->
                    mb.of(MethodId.of(dispatcherClassId, "forwardMsf", ProtoId.of(TypeId.V, respHandlerType, TypeId.OBJECT)))
                        .withFlags(ACC_PUBLIC or ACC_STATIC)
                        .withCode(2) { ib ->
                            ib.const_wide(ib.l(0), 0x1122334455667788L)
                            ib.invoke(VIRTUAL, dispatchMethod, ib.p(0), ib.p(1))
                            ib.return_void()
                        }
                }
                .withMethod { mb ->
                    mb.of(MethodId.of(dispatcherClassId, "deliverToNt", ProtoId.of(TypeId.V, sessionIfaceType, TypeId.of(String::class.java), TypeId.of(ByteArray::class.java))))
                        .withFlags(ACC_PUBLIC or ACC_STATIC)
                        .withCode(0) { ib ->
                            ib.invoke(INTERFACE, onMsfPushMethod, ib.p(0), ib.p(1), ib.p(2))
                            ib.return_void()
                        }
                }
                .withMethod { mb ->
                    mb.of(MethodId.of(dispatcherClassId, "unrelatedWork", ProtoId.of(TypeId.V, TypeId.OBJECT)))
                        .withFlags(ACC_PUBLIC or ACC_STATIC)
                        .withCode(2) { ib ->
                            ib.const_wide(ib.l(0), 42L)
                            ib.invoke(VIRTUAL, unrelatedMethod, ib.p(0))
                            ib.return_void()
                        }
                }
        }

        val dexBytes = DexIO.write(writeOptions, Dex.of(dispatcherDef))
        val targets = setOf(
            DexCallerScanner.TargetMethod("Lmqq/app/msghandle/MsgRespHandler;", "dispatchRespMsg"),
            DexCallerScanner.TargetMethod("Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession;", "onMsfPush"),
        )

        val callers = DexCallerScanner.scanDex(dexBytes, targets)
        assertEquals(2, callers.size)
        assertTrue(
            callers.contains(
                DexCallerScanner.CallerMethod(
                    className = "com.example.FakeDispatcher",
                    methodName = "forwardMsf",
                    parameterTypes = listOf("mqq.app.msghandle.MsgRespHandler", "java.lang.Object"),
                ),
            ),
        )
        assertTrue(
            callers.contains(
                DexCallerScanner.CallerMethod(
                    className = "com.example.FakeDispatcher",
                    methodName = "deliverToNt",
                    parameterTypes = listOf(
                        "com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession",
                        "java.lang.String",
                        "[B",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `scanApkOrDex skips unrelated dex entries and version cache round-trips and invalidates cleanly`() {
        val tempDir = Files.createTempDirectory("dex-caller-test-").toFile()
        try {
            val targetType = TypeId.ofName("mqq.app.msghandle.MsgRespHandler")
            val targetMethod = MethodId.of(targetType, "dispatchRespMsg", ProtoId.of(TypeId.V, TypeId.OBJECT))
            val callerType = TypeId.ofName("com.example.BridgeCaller")
            val callerDef = ClassBuilder.build(callerType) { cb ->
                cb.withSuperClass(TypeId.OBJECT)
                    .withFlags(ACC_PUBLIC)
                    .withMethod { mb ->
                        mb.of(MethodId.of(callerType, "runDispatch", ProtoId.of(TypeId.V, targetType, TypeId.OBJECT)))
                            .withFlags(ACC_PUBLIC or ACC_STATIC)
                            .withCode(0) { ib ->
                                ib.invoke(VIRTUAL, targetMethod, ib.p(0), ib.p(1))
                                ib.return_void()
                            }
                    }
            }
            val unrelatedType = TypeId.ofName("com.example.OtherClass")
            val unrelatedDef = ClassBuilder.build(unrelatedType) { cb ->
                cb.withSuperClass(TypeId.OBJECT)
                    .withFlags(ACC_PUBLIC)
                    .withMethod { mb ->
                        mb.of(MethodId.of(unrelatedType, "noop", ProtoId.of(TypeId.V)))
                            .withFlags(ACC_PUBLIC or ACC_STATIC)
                            .withCode(0) { ib -> ib.return_void() }
                    }
            }

            val apk = File(tempDir, "host.apk")
            ZipOutputStream(FileOutputStream(apk)).use { zos ->
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write(DexIO.write(writeOptions, Dex.of(unrelatedDef)))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("classes2.dex"))
                zos.write(DexIO.write(writeOptions, Dex.of(callerDef)))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("assets/ignore.txt"))
                zos.write("not a dex".toByteArray())
                zos.closeEntry()
            }

            val targets = setOf(DexCallerScanner.TargetMethod("Lmqq/app/msghandle/MsgRespHandler;", "dispatchRespMsg"))
            val found = DexCallerScanner.scanApkOrDex(apk, targets)
            assertEquals(1, found.size)
            assertEquals("com.example.BridgeCaller", found.single().className)
            assertEquals("runDispatch", found.single().methodName)

            val cacheDir = File(tempDir, "files")
            DexCallerScanner.saveCachedCallers(cacheDir, 9001L, "9.3.70", "anti-revoke", targets, found)
            assertEquals(found, DexCallerScanner.loadCachedCallers(cacheDir, 9001L, "9.3.70", "anti-revoke", targets))
            // 版本或目标变更后缓存自动失效
            assertNull(DexCallerScanner.loadCachedCallers(cacheDir, 9002L, "9.3.70", "anti-revoke", targets))
            assertNull(DexCallerScanner.loadCachedCallers(cacheDir, 9001L, "9.3.75", "anti-revoke", targets))
            val changedTargets = targets + DexCallerScanner.TargetMethod("Lcom/example/Extra;", "foo")
            assertNull(DexCallerScanner.loadCachedCallers(cacheDir, 9001L, "9.3.70", "anti-revoke", changedTargets))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
