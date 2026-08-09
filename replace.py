file_path = '/app/applet/app/src/main/java/com/example/MainActivity.kt'
with open(file_path, 'r') as f:
    lines = f.readlines()

out = []
skip = False
for i, line in enumerate(lines):
    if 'val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)' in line:
        skip = True
        out.append(line)
        out.append('                                if (result != null) {\n')
        out.append('                                    val parsedLayout = com.example.domain.parser.LayoutParser.parseGeminiResponse(result)\n')
        out.append('                                    if (parsedLayout != null) {\n')
        out.append('                                        recognizeResult = "Udało się zmapować układ!\\nLiczba paneli: ${parsedLayout.panels.size}\\n\\nSurowy JSON:\\n$result"\n')
        out.append('                                    } else {\n')
        out.append('                                        recognizeResult = "Zwrócono wynik, ale parsowanie zawiodło:\\n$result"\n')
        out.append('                                    }\n')
        out.append('                                } else {\n')
        out.append('                                    recognizeResult = "Zwrócono pusty wynik z Gemini."\n')
        out.append('                                }\n')
        continue
    if skip:
        if 'recognizeResult = ' in line and 'Zwrócono wynik' in line: continue
        if 'recognizeResult = ' in line and 'Zwrócono pusty' in line: continue
        if 'recognizeResult = ' in line and 'Udało się zmapować' in line: continue
        if '} else {' in line:
            skip = False
        else:
            continue
    out.append(line)

with open(file_path, 'w') as f:
    f.writelines(out)
