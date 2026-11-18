package com.multitrack.app.audio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 波形峰值提取：导入时按基准分辨率预计算 min/max，绘制时二次聚合成像素分辨率。
 * 【从鸿蒙版 PeakExtractor.ets 平移】
 */
object PeakExtractor {

    private val POOL = java.util.concurrent.Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors()).coerceIn(2, 4)
    )

    private fun threads() = (Runtime.getRuntime().availableProcessors()).coerceIn(2, 4)

    /** 计算基准峰值，返回 interleaved [min0,max0,min1,max1,...]，取值 [-1,1] */
    fun computeBasePeaks(buf: PcmBuffer): FloatArray {
        val frames = buf.frames
        val buckets = max(1, ceil(frames.toDouble() / C.PEAK_BUCKET).toInt())
        val peaks = FloatArray(buckets * 2)
        for (b in 0 until buckets) {
            val start = b * C.PEAK_BUCKET
            val end = min(frames, start + C.PEAK_BUCKET)
            var mn = 0f; var mx = 0f
            for (i in start until end) {
                val l = buf.left[i]; val r = buf.right[i]
                if (l < mn) mn = l
                if (l > mx) mx = l
                if (r < mn) mn = r
                if (r > mx) mx = r
            }
            peaks[b * 2] = mn
            peaks[b * 2 + 1] = mx
        }
        return peaks
    }

    /** 一次遍历同时得到合并 / 左 / 右三套峰值（分片多线程，长音频导入更快） */
    fun computeAll(buf: PcmBuffer): Triple<FloatArray, FloatArray, FloatArray> {
        val frames = buf.frames
        val buckets = max(1, ceil(frames.toDouble() / C.PEAK_BUCKET).toInt())
        val mono = FloatArray(buckets * 2)
        val pl = FloatArray(buckets * 2)
        val pr = FloatArray(buckets * 2)
        val nThreads = threads().coerceAtMost(buckets)
        if (buckets < 1024 || nThreads <= 1) {
            return computeAllSerial(buf, mono, pl, pr, buckets, frames)
        }
        val chunk = (buckets + nThreads - 1) / nThreads
        val futures = (0 until nThreads).map { t ->
            val from = t * chunk
            val to = min(buckets, (t + 1) * chunk)
            POOL.submit {
                if (from < to) computeRange(buf, mono, pl, pr, from, to, frames)
            }
        }
        futures.forEach { it.get() }
        return Triple(mono, pl, pr)
    }

    private fun computeAllSerial(
        buf: PcmBuffer, mono: FloatArray, pl: FloatArray, pr: FloatArray,
        buckets: Int, frames: Int
    ): Triple<FloatArray, FloatArray, FloatArray> {
        computeRange(buf, mono, pl, pr, 0, buckets, frames)
        return Triple(mono, pl, pr)
    }

    private fun computeRange(
        buf: PcmBuffer, mono: FloatArray, pl: FloatArray, pr: FloatArray,
        fromBucket: Int, toBucket: Int, frames: Int
    ) {
        for (b in fromBucket until toBucket) {
            val start = b * C.PEAK_BUCKET
            val end = min(frames, start + C.PEAK_BUCKET)
            var mn = 0f; var mx = 0f
            var mnL = 0f; var mxL = 0f; var mnR = 0f; var mxR = 0f
            for (i in start until end) {
                val l = buf.left[i]
                val r = buf.right[i]
                if (l < mnL) mnL = l
                if (l > mxL) mxL = l
                if (r < mnR) mnR = r
                if (r > mxR) mxR = r
                if (l < mn) mn = l
                if (l > mx) mx = l
                if (r < mn) mn = r
                if (r > mx) mx = r
            }
            mono[b * 2] = mn; mono[b * 2 + 1] = mx
            pl[b * 2] = mnL; pl[b * 2 + 1] = mxL
            pr[b * 2] = mnR; pr[b * 2 + 1] = mxR
        }
    }

    private fun computeAllOld(buf: PcmBuffer): Triple<FloatArray, FloatArray, FloatArray> {
        val frames = buf.frames
        val buckets = max(1, ceil(frames.toDouble() / C.PEAK_BUCKET).toInt())
        val mono = FloatArray(buckets * 2)
        val pl = FloatArray(buckets * 2)
        val pr = FloatArray(buckets * 2)
        for (b in 0 until buckets) {
            val start = b * C.PEAK_BUCKET
            val end = min(frames, start + C.PEAK_BUCKET)
            var mn = 0f; var mx = 0f
            var mnL = 0f; var mxL = 0f; var mnR = 0f; var mxR = 0f
            for (i in start until end) {
                val l = buf.left[i]
                val r = buf.right[i]
                if (l < mnL) mnL = l
                if (l > mxL) mxL = l
                if (r < mnR) mnR = r
                if (r > mxR) mxR = r
                if (l < mn) mn = l
                if (l > mx) mx = l
                if (r < mn) mn = r
                if (r > mx) mx = r
            }
            mono[b * 2] = mn; mono[b * 2 + 1] = mx
            pl[b * 2] = mnL; pl[b * 2 + 1] = mxL
            pr[b * 2] = mnR; pr[b * 2 + 1] = mxR
        }
        return Triple(mono, pl, pr)
    }

    /** 多线程分片计算峰值：按桶区间切片，线程间互不重叠，长音频导入快数倍 */
    fun computeAllParallel(buf: PcmBuffer, threads: Int = defaultThreads()): Triple<FloatArray, FloatArray, FloatArray> {
        val frames = buf.frames
        if (frames < C.PEAK_BUCKET * 8 || threads <= 1) return computeAll(buf)
        val buckets = max(1, ceil(frames.toDouble() / C.PEAK_BUCKET).toInt())
        val mono = FloatArray(buckets * 2)
        val pl = FloatArray(buckets * 2)
        val pr = FloatArray(buckets * 2)
        val chunk = (buckets + threads - 1) / threads
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val latch = java.util.concurrent.CountDownLatch((buckets + chunk - 1) / chunk)
        var b0 = 0
        while (b0 < buckets) {
            val from = b0
            val to = min(buckets, b0 + chunk)
            pool.execute {
                for (b in from until to) {
                    val start = b * C.PEAK_BUCKET
                    val end = min(frames, start + C.PEAK_BUCKET)
                    var mn = 0f; var mx = 0f
                    var mnL = 0f; var mxL = 0f; var mnR = 0f; var mxR = 0f
                    for (i in start until end) {
                        val l = buf.left[i]
                        val r = buf.right[i]
                        if (l < mnL) mnL = l
                        if (l > mxL) mxL = l
                        if (r < mnR) mnR = r
                        if (r > mxR) mxR = r
                        if (l < mn) mn = l
                        if (l > mx) mx = l
                        if (r < mn) mn = r
                        if (r > mx) mx = r
                    }
                    mono[b * 2] = mn; mono[b * 2 + 1] = mx
                    pl[b * 2] = mnL; pl[b * 2 + 1] = mxL
                    pr[b * 2] = mnR; pr[b * 2 + 1] = mxR
                }
                latch.countDown()
            }
            b0 = to
        }
        latch.await()
        pool.shutdown()
        return Triple(mono, pl, pr)
    }

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** 左右声道分开的基线峰值（双轨波形用） */
    fun computeStereoPeaks(buf: PcmBuffer): Pair<FloatArray, FloatArray> {
        val frames = buf.frames
        val buckets = max(1, ceil(frames.toDouble() / C.PEAK_BUCKET).toInt())
        val pl = FloatArray(buckets * 2)
        val pr = FloatArray(buckets * 2)
        for (b in 0 until buckets) {
            val start = b * C.PEAK_BUCKET
            val end = min(frames, start + C.PEAK_BUCKET)
            var mnL = 0f; var mxL = 0f; var mnR = 0f; var mxR = 0f
            for (i in start until end) {
                val l = buf.left[i]
                val r = buf.right[i]
                if (l < mnL) mnL = l
                if (l > mxL) mxL = l
                if (r < mnR) mnR = r
                if (r > mxR) mxR = r
            }
            pl[b * 2] = mnL; pl[b * 2 + 1] = mxL
            pr[b * 2] = mnR; pr[b * 2 + 1] = mxR
        }
        return pl to pr
    }

    /** 把基准峰值聚合成指定像素宽度的显示峰值 */
    fun aggregatePeaks(
        basePeaks: FloatArray,
        totalFrames: Int,
        startFrame: Int,
        endFrame: Int,
        pixelWidth: Int
    ): FloatArray {
        val buckets = basePeaks.size / 2
        val pxCount = max(1, pixelWidth)
        val out = FloatArray(pxCount * 2)
        val span = max(1, endFrame - startFrame)
        for (px in 0 until pxCount) {
            val f0 = startFrame + (px.toDouble() / pxCount) * span
            val f1 = startFrame + ((px + 1.0) / pxCount) * span
            val b0 = floor((f0 / totalFrames) * buckets).toInt()
            val b1 = max(b0 + 1, floor((f1 / totalFrames) * buckets).toInt())
            var mn = 0f; var mx = 0f
            for (b in max(0, b0) until min(buckets, b1)) {
                val vmin = basePeaks[b * 2]
                val vmax = basePeaks[b * 2 + 1]
                if (vmin < mn) mn = vmin
                if (vmax > mx) mx = vmax
            }
            out[px * 2] = mn
            out[px * 2 + 1] = mx
        }
        return out
    }
}
