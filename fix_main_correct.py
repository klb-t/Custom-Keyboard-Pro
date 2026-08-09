file_path = '/app/applet/app/src/main/java/com/example/MainActivity.kt'
with open(file_path, 'r') as f:
    content = f.read()

import re

# Find the start of the block
start_marker = "            val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult("
end_marker = "            Button(onClick = { imagePickerLauncher.launch(\"image/*\") }"

start_idx = content.find(start_marker)
end_idx = content.find(end_marker)

if start_idx != -1 and end_idx != -1:
    new_block = """            val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    recognizeResult = "Przetwarzanie obrazu przez Gemini API..."
                    scope.launch {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (bitmap != null) {
                                val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)
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
                                recognizeResult = "Błąd: nie można załadować obrazu."
                            }
                        } catch (e: Exception) {
                            recognizeResult = "Błąd: ${e.message}"
                        }
                    }
                }
            }

"""
    new_content = content[:start_idx] + new_block + content[end_idx:]
    with open(file_path, 'w') as f:
        f.write(new_content)
    print("Fixed!")
else:
    print("Could not find markers")

# Remove extra bracket at the end
with open(file_path, 'r') as f:
    content2 = f.read()
if content2.strip().endswith('}'):
    content2 = content2.rstrip()[:-1]
    with open(file_path, 'w') as f:
        f.write(content2)
