import com.example.core.assistant.GoalPlanTest

fun main(args: Array<String>) {
    val instances = listOf(GoalPlanTest(), com.example.core.assistant.GoalSessionTest(),
        com.example.core.assistant.GoalAgentTest(), com.example.core.devices.ReactiveLightTest())
    var count = 0; var failed = 0
    for (instance in instances) for (test in instance.javaClass.declaredMethods.filter {
        it.getAnnotation(org.junit.Test::class.java) != null
    }.sortedBy { it.name }) {
        count++
        try { test.invoke(instance); println("PASS ${test.name}") }
        catch (e: java.lang.reflect.InvocationTargetException) { failed++; println("FAIL ${test.name}: ${e.cause}") }
    }
    println("${count-failed}/$count standalone goal-core tests passed; this is not the Android/full project suite")
    check(failed == 0)
    val protocol = listOf(com.example.core.devices.YeelightProtocol.properties(1),
        com.example.core.devices.YeelightProtocol.music(2, "192.168.1.2", 45000),
        com.example.core.devices.YeelightProtocol.colour(3, com.example.core.devices.LightValue(0xff0000, 30)))
    java.io.File(args.firstOrNull() ?: "build/goal-checks/light-requests.json").apply {
        parentFile?.mkdirs(); writeText(protocol.joinToString(",", "[", "]") { it.trim() })
    }
}
