package com.nabz.news

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val iranTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Tehran")

fun formatIranDateTime(timestamp: Long): String {
    val date = Date(timestamp)
    val g = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = iranTimeZone }
    val parts = g.format(date).split(' ', '-')
    val gy = parts[0].toInt()
    val gm = parts[1].toInt()
    val gd = parts[2].toInt()
    val hhmm = parts[3]
    val (jy, jm, jd) = gregorianToJalali(gy, gm, gd)
    return "%04d/%02d/%02d • %s".format(Locale.US, jy, jm, jd, hhmm)
}

private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
    val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)
    var gy2 = gy - 1600
    val gm2 = gm - 1
    val gd2 = gd - 1
    var gDayNo = 365 * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
    for (i in 0 until gm2) gDayNo += gDaysInMonth[i]
    if (gm2 > 1 && (gy % 4 == 0 && gy % 100 != 0 || gy % 400 == 0)) gDayNo++
    gDayNo += gd2

    var jDayNo = gDayNo - 79
    val jNp = jDayNo / 12053
    jDayNo %= 12053
    var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
    jDayNo %= 1461
    if (jDayNo >= 366) {
        jy += (jDayNo - 1) / 365
        jDayNo = (jDayNo - 1) % 365
    }
    var jm = 0
    while (jm < 11 && jDayNo >= jDaysInMonth[jm]) {
        jDayNo -= jDaysInMonth[jm]
        jm++
    }
    val jd = jDayNo + 1
    return Triple(jy, jm + 1, jd)
}
