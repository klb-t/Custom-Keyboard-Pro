package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipProcessor {
    fun processZip(context: Context, uri: Uri, onResult: (String) -> Unit) {
        val outDir = File(context.filesDir, "layouts")
        if (!outDir.exists()) outDir.mkdirs()

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val zis = ZipInputStream(inputStream)
            var entry = zis.nextEntry
            var count = 0
            var mapCount = 0
            
            while (entry != null) {
                if (!entry.isDirectory && (entry.name.endsWith(".png") || entry.name.endsWith(".jpg"))) {
                    val bmp = BitmapFactory.decodeStream(zis)
                    if (bmp != null) {
                        val cleanedBmp = cleanArtifacts(bmp)
                        val fileName = entry.name.substringAfterLast("/")
                        
                        // Check if it's a grayscale sensitivity map
                        if (isGrayscale(cleanedBmp)) {
                            // Sensitivity map (256 shades of gray)
                            val mapDir = File(context.filesDir, "sensitivity_maps")
                            if (!mapDir.exists()) mapDir.mkdirs()
                            val outFile = File(mapDir, fileName)
                            val fos = FileOutputStream(outFile)
                            cleanedBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            fos.close()
                            mapCount++
                        } else {
                            // Regular layout
                            val outFile = File(outDir, fileName)
                            val fos = FileOutputStream(outFile)
                            cleanedBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            fos.close()
                            count++
                        }
                    }
                }
                entry = zis.nextEntry
            }
            zis.close()
            onResult("Przetworzono $count układów oraz $mapCount map czułości z paczki ZIP.")
        } catch (e: Exception) {
            onResult("Błąd: ${e.message}")
        }
    }

    private fun isGrayscale(bitmap: Bitmap): Boolean {
        // Sprawdzamy próbkę pikseli, czy obraz jest w skali szarości
        var isGray = true
        val width = bitmap.width
        val height = bitmap.height
        val stepX = maxOf(1, width / 20)
        val stepY = maxOf(1, height / 20)
        
        for (x in 0 until width step stepX) {
            for (y in 0 until height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (Math.abs(r - g) > 10 || Math.abs(r - b) > 10 || Math.abs(g - b) > 10) {
                    isGray = false
                    break
                }
            }
            if (!isGray) break
        }
        return isGray
    }

    private fun cleanArtifacts(src: Bitmap): Bitmap {
        // Usuwanie artefaktów: ucinamy górne 40% (interfejs aplikacji) i dolne 5% (pasek nawigacji)
        val width = src.width
        val height = src.height
        
        val startY = (height * 0.4).toInt() 
        val endY = (height * 0.95).toInt() 
        
        val newHeight = endY - startY
        if (newHeight <= 0) return src
        
        return Bitmap.createBitmap(src, 0, startY, width, newHeight)
    }
}
