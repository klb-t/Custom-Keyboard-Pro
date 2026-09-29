import com.example.core.capture.ConversationArchive
import com.example.core.capture.CaptureOptions
import com.example.core.capture.CaptureFrame
import com.example.core.capture.CapturedNode
import com.example.core.capture.ConversationCaptureTest

fun main(args: Array<String>) {
    val instance = ConversationCaptureTest()
    val tests = instance.javaClass.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }.sortedBy { it.name }
    var failed = 0
    for (test in tests) {
        try { test.invoke(instance); println("PASS ${test.name}") }
        catch (e: java.lang.reflect.InvocationTargetException) { failed++; println("FAIL ${test.name}: ${e.cause}") }
    }
    val a = ConversationArchive("test.chat", 7, "2026-09-29", CaptureOptions())
    a.append(CaptureFrame(0, "collecting", listOf(CapturedNode("0", null, "Line\n\"quoted\"\\\u0000😀"))))
    a.finish("fixture")
    java.io.File(args.firstOrNull() ?: "build/capture-checks/export-fixture.json").apply { parentFile?.mkdirs(); writeText(a.json()) }
    println("${tests.size - failed}/${tests.size} standalone regression tests passed")
    check(failed == 0)
}
