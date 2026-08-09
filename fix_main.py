import sys

file_path = '/app/applet/app/src/main/java/com/example/MainActivity.kt'
with open(file_path, 'r') as f:
    content = f.read()

# Just replace the whole block by finding it. It's inside a coroutine:
#                             if (bitmap != null) {
# ...
#                             } else {

start_idx = content.find('val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)')
if start_idx != -1:
    end_idx = content.find('} else {', start_idx)
    end_idx = content.find('recognizeResult = "Błąd: nie można załadować obrazu."', end_idx)
    
    # Actually let's just find the exact text we want to replace
    # We will search for a regex.
import re
new_code = '''val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)
                                if (result != null) {
                                    val parsedLayout = com.example.domain.parser.LayoutParser.parseGeminiResponse(result)
                                    if (parsedLayout != null) {
                                        recognizeResult = "Udało się zmapować układ!\\nLiczba paneli: ${parsedLayout.panels.size}\\n\\nSurowy JSON:\\n$result"
                                    } else {
                                        recognizeResult = "Zwrócono wynik, ale parsowanie zawiodło:\\n$result"
                                    }
                                } else {
                                    recognizeResult = "Zwrócono pusty wynik z Gemini."
                                }
                            } else {
                                recognizeResult = "Błąd: nie można załadować obrazu."'''
    
# Let's just restore the file from git or a backup, wait there is no git.
