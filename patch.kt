--- /app/applet/app/src/main/java/com/example/MainActivity.kt
+++ /app/applet/app/src/main/java/com/example/MainActivity.kt
@@ -472,7 +472,13 @@
                             val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                             inputStream?.close()
                             if (bitmap != null) {
-                                val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)
-                                recognizeResult = result
+                                val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)
+                                if (result != null) {
+                                    val parsedLayout = com.example.domain.parser.LayoutParser.parseGeminiResponse(result)
+                                    if (parsedLayout != null) {
+                                        recognizeResult = "Udało się zmapować układ!\nLiczba paneli: \${parsedLayout.panels.size}\n\nSurowy JSON:\n\$result"
+                                    } else {
+                                        recognizeResult = "Zwrócono wynik, ale parsowanie zawiodło:\n\$result"
+                                    }
+                                } else {
+                                    recognizeResult = "Zwrócono pusty wynik z Gemini."
+                                }
                             } else {
                                 recognizeResult = "Błąd: nie można załadować obrazu."
