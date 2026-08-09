package com.example.domain

import com.example.domain.action.*
import com.example.domain.clipboard.*
import com.example.domain.history.*
import com.example.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ArchitectureTest {

    @Test
    fun testHistoryManager_undoRedo_truncation() {
        val manager = HistoryManager()
        
        val opA = KeyboardOperation.MovePanel(panelId = "p1", newConstraint = PositionConstraint.Floating)
        val opB = KeyboardOperation.MovePanel(panelId = "p2", newConstraint = PositionConstraint.Floating)
        val opC = KeyboardOperation.MovePanel(panelId = "p3", newConstraint = PositionConstraint.Floating)
        
        manager.execute(opA)
        manager.execute(opB)
        manager.execute(opC)
        
        // A, B, C
        assertEquals(3, manager.getActiveOperations().size)
        
        manager.undo()
        manager.undo()
        
        // Only A is active
        assertEquals(1, manager.getActiveOperations().size)
        
        val opD = KeyboardOperation.MovePanel(panelId = "p4", newConstraint = PositionConstraint.Floating)
        manager.execute(opD)
        
        // A, D
        val activeOps = manager.getActiveOperations()
        assertEquals(2, activeOps.size)
        assertEquals(opA.id, activeOps[0].id)
        assertEquals(opD.id, activeOps[1].id)
        
        // Redo should do nothing
        manager.redo()
        assertEquals(2, manager.getActiveOperations().size)
        assertEquals(opD.id, manager.getActiveOperations()[1].id)
        
        // Underlying journal size
        assertEquals(2, manager.getJournalSize())
    }

    @Test
    fun testCanonicalReferenceIntegrity() {
        // Build document
        val actionId = "action-1"
        val action = ActionDefinition.CommitText(id = actionId, text = "Hello")
        
        val elementId = "element-1"
        val element = Element.Key(
            id = elementId,
            name = "Key_A",
            visual = VisualRepresentation(label = "A"),
            sensitivity = null,
            interactionBehavior = InteractionBehavior(onTapRef = ActionRef(actionId))
        )
        
        val groupId = "group-1"
        val group = Group(
            id = groupId,
            name = "MainGroup",
            elements = listOf(ElementReference(elementId = elementId))
        )
        
        val panelId = "panel-1"
        val panel = Panel(
            id = panelId,
            name = "MainPanel",
            positionConstraint = PositionConstraint.AnchorToEdge(Edge.Bottom),
            content = PanelContent.GroupRef(GroupReference(groupId = groupId))
        )
        
        val layoutId = "layout-1"
        val layout = Layout(
            id = layoutId,
            name = "DefaultLayout",
            panels = listOf(PanelReference(panelId = panelId))
        )
        
        val document = KeyboardDocument(
            schemaVersion = 1,
            workspace = Workspace(name = "My Workspace", activeLayoutId = layoutId),
            layoutRegistry = mapOf(layoutId to layout),
            panelRegistry = mapOf(panelId to panel),
            groupRegistry = mapOf(groupId to group),
            elementRegistry = mapOf(elementId to element),
            actionRegistry = mapOf(actionId to action)
        )
        
        assertNotNull(document.layoutRegistry[layoutId])
        assertNotNull(document.panelRegistry[panelId])
        assertNotNull(document.groupRegistry[groupId])
        
        // Reusable action check
        val actionRefId = (document.elementRegistry[elementId] as Element.Key).interactionBehavior.onTapRef?.actionId
        assertEquals(actionId, actionRefId)
        val resolvedAction = document.actionRegistry[actionRefId]
        assertTrue(resolvedAction is ActionDefinition.CommitText)
    }

    @Test
    fun testClipboardRepresentations() {
        val item = ClipboardItem(
            representations = listOf(
                ContentRepresentation.InlineText(text = "Hello"),
                ContentRepresentation.UriReference(mimeType = "image/png", uri = "content://media/1"),
                ContentRepresentation.LocalMaterializedFile(mimeType = "video/mp4", filePath = "/data/cache/vid.mp4")
            )
        )
        
        assertEquals(3, item.representations.size)
        assertTrue(item.representations[0] is ContentRepresentation.InlineText)
        assertTrue(item.representations[1] is ContentRepresentation.UriReference)
        assertTrue(item.representations[2] is ContentRepresentation.LocalMaterializedFile)
    }
}
