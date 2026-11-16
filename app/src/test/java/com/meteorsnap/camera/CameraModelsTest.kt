package com.meteorsnap.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModelsTest {

    @Test
    fun `显式来源值可以稳定恢复`() {
        assertEquals(CameraSource.PHONE, CameraSource.fromPreference("phone"))
        assertEquals(CameraSource.USB, CameraSource.fromPreference("usb"))
    }

    @Test
    fun `旧版本保存过USB路径时迁移为外挂来源`() {
        assertEquals(
            CameraSource.USB,
            CameraSource.fromPreference(value = null, legacyUsbDeviceName = "/dev/bus/usb/001/002")
        )
    }

    @Test
    fun `新安装没有历史字段时默认手机来源`() {
        assertEquals(CameraSource.PHONE, CameraSource.fromPreference(value = null))
        assertEquals(CameraSource.PHONE, CameraSource.fromPreference(value = "unknown"))
    }

    @Test
    fun `后置和前置摄像头旋转矩阵按屏幕方向计算`() {
        assertEquals(90, camera2VideoRotationDegrees(90, 0, isFrontFacing = false))
        assertEquals(0, camera2VideoRotationDegrees(90, 90, isFrontFacing = false))
        assertEquals(270, camera2VideoRotationDegrees(270, 0, isFrontFacing = true))
        assertEquals(0, camera2VideoRotationDegrees(270, 90, isFrontFacing = true))
    }
}
