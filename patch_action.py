import re

with open("app/src/main/java/com/example/domain/action/Action.kt", "r") as f:
    content = f.read()

new_content = content.replace(
    "}\n\n/**",
    "    data class SelectionAction(\n        override val id: String = UUID.randomUUID().toString(),\n        val intent: com.example.domain.selection.SelectionIntent\n    ) : ActionDefinition\n}\n\n/**"
)

with open("app/src/main/java/com/example/domain/action/Action.kt", "w") as f:
    f.write(new_content)
