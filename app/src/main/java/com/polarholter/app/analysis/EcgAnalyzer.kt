package com.polarholter.app.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * =====================================================================
 * ВАЖНО — ГРАНИЦЫ ЭТОГО МОДУЛЯ (прочитать перед изменением)
 * =====================================================================
 * Polar H10 — ОДНО отведение (аналог по вектору отведению II). На одном
 * отведении принципиально нельзя:
 *   - определить электрическую ось сердца (ЭОС) — нужен фронтальный
 *     треугольник минимум из 3 отведений;
 *   - поставить точный тип блокады ножки пучка Гиса — нужна морфология
 *     в грудных отведениях V1/V6;
 *   - надёжно подтвердить "синусовый ритм" — нужен чистый зубец P перед
 *     каждым QRS, что на грудном ремне часто тонет в шуме.
 *
 * Поэтому этот модуль сознательно НЕ ставит диагнозы. Он считает
 * измеримые характеристики (ЧСС, ширина QRS, регулярность R-R) и
 * помечает участки, которые стоит посмотреть врачу — с формулировками
 * "возможно", "для оценки врачом", а не "блокада ЛНПГ" или "ЭОС нормальная".
 * Не убирайте эти оговорки при доработке — это не формальность, а то,
 * что отличает инструмент скрининга от вводящего в заблуждение медизделия.
 * =====================================================================
 */

data class RPeak(val sampleIdx: Long, val timeSec: Double, val amplitudeUv: Int)

enum class FlagType {
    IRREGULAR_RHYTHM,      // резкий скачок R-R относительно соседних интервалов
    LONG_PAUSE,            // R-R существенно длиннее локального среднего
    WIDE_QRS,               // ширина QRS > 120 мс
    TACHYCARDIA,            // ЧСС > 100 на протяжении окна
    BRADYCARDIA,             // ЧСС < 50 на протяжении окна
    POOR_SIGNAL_QUALITY      // амплитуда/шум вне разумного диапазона — не аритмия, а плохой контакт
}

data class EcgFlag(
    val type: FlagType,
    val timeSec: Double,
    val message: String
)

class EcgAnalyzer(private val sampleRateHz: Int = 130) {

    private val rPeaks = ArrayDeque<RPeak>()
    private var sampleIdx = 0L

    // простой адаптивный порог для детекции R-зубца
    private var runningMax = 300.0
    private val recentQrsBuffer = ArrayDeque<Int>() // для оценки ширины QRS вокруг пика

    val flags = mutableListOf<EcgFlag>()

    /** Подать один отфильтрованный отсчёт ЭКГ (мкВ). Возвращает флаг, если он сработал на этом отсчёте. */
    fun pushSample(valueUv: Int): EcgFlag? {
        val t = sampleIdx / sampleRateHz.toDouble()
        recentQrsBuffer.addLast(valueUv)
        if (recentQrsBuffer.size > sampleRateHz) recentQrsBuffer.removeFirst() // держим ~1с истории

        var flag: EcgFlag? = null

        val absVal = abs(valueUv)
        if (absVal > runningMax * 0.6) runningMax = absVal * 1.3
        val threshold = runningMax * 0.45

        if (absVal > threshold && isLocalMax(valueUv)) {
            val peak = RPeak(sampleIdx, t, valueUv)
            flag = onNewPeak(peak)
        }

        // грубая проверка качества сигнала: сигнал "залип" или зашкаливает
        if (absVal > 6000) {
            flag = EcgFlag(FlagType.POOR_SIGNAL_QUALITY, t, "Возможен артефакт/отрыв электрода — участок для проверки качества, не для клинической оценки")
            flags.add(flag)
        }

        sampleIdx++
        return flag
    }

    private var lastSample = 0
    private var lastWasRising = false
    private fun isLocalMax(v: Int): Boolean {
        val rising = v > lastSample
        val wasPeak = lastWasRising && !rising
        lastWasRising = rising
        lastSample = v
        return wasPeak
    }

    private fun onNewPeak(peak: RPeak): EcgFlag? {
        rPeaks.addLast(peak)
        if (rPeaks.size > 16) rPeaks.removeFirst()
        if (rPeaks.size < 3) return null

        val n = rPeaks.size
        val rr1 = rPeaks[n - 1].timeSec - rPeaks[n - 2].timeSec
        val rr2 = rPeaks[n - 2].timeSec - rPeaks[n - 3].timeSec
        if (rr1 <= 0 || rr2 <= 0) return null

        val bpm = (60.0 / rr1).roundToInt()

        // резкая нерегулярность: текущий интервал отличается от предыдущего более чем на 20%
        val relChange = abs(rr1 - rr2) / rr2
        var newFlag: EcgFlag? = null
        if (relChange > 0.20) {
            newFlag = EcgFlag(FlagType.IRREGULAR_RHYTHM, peak.timeSec,
                "Резкое изменение R-R интервала (%.0f%%) — возможна экстрасистола, для оценки врачом".format(relChange * 100))
        } else if (rr1 > 2.0) {
            newFlag = EcgFlag(FlagType.LONG_PAUSE, peak.timeSec,
                "Пауза между сокращениями ${"%.1f".format(rr1)} c — участок для внимательного просмотра")
        } else if (bpm > 100) {
            newFlag = EcgFlag(FlagType.TACHYCARDIA, peak.timeSec, "ЧСС $bpm уд/мин — возможная тахикардия")
        } else if (bpm < 50) {
            newFlag = EcgFlag(FlagType.BRADYCARDIA, peak.timeSec, "ЧСС $bpm уд/мин — возможная брадикардия")
        }

        val qrsWidthMs = estimateQrsWidthMs(peak)
        if (qrsWidthMs != null && qrsWidthMs > 120) {
            val wideFlag = EcgFlag(FlagType.WIDE_QRS, peak.timeSec,
                "QRS расширен: $qrsWidthMs мс (>120 мс) — возможное нарушение внутрижелудочковой проводимости, " +
                "тип определить по одному отведению нельзя — требуется 12-канальная ЭКГ для уточнения")
            flags.add(wideFlag)
            newFlag = newFlag ?: wideFlag
        }

        newFlag?.let { flags.add(it) }
        return newFlag
    }

    /** Грубая оценка ширины QRS: ищем возврат сигнала к базовой линии по обе стороны от пика. */
    private fun estimateQrsWidthMs(peak: RPeak): Int? {
        val buf = recentQrsBuffer.toIntArray()
        if (buf.isEmpty()) return null
        val baseline = buf.average()
        val noiseband = buf.map { abs(it - baseline) }.average() * 0.3

        val peakLocalIdx = buf.size - 1 // приблизительно: текущий отсчёт = конец буфера
        var left = peakLocalIdx
        while (left > 0 && abs(buf[left] - baseline) > noiseband) left--
        var right = peakLocalIdx
        while (right < buf.size - 1 && abs(buf[right] - baseline) > noiseband) right++

        val widthSamples = right - left
        if (widthSamples <= 0) return null
        return ((widthSamples * 1000.0) / sampleRateHz).roundToInt()
    }

    fun reset() {
        rPeaks.clear()
        flags.clear()
        sampleIdx = 0
        runningMax = 300.0
        recentQrsBuffer.clear()
        lastSample = 0
        lastWasRising = false
    }
}
