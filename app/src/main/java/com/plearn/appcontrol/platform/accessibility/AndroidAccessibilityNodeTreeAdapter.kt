package com.plearn.appcontrol.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import java.util.UUID

class AndroidAccessibilityNodeTreeAdapter(
    private val registry: AccessibilityServiceRegistry,
) : NodeTreeAdapter {
    override fun isAvailable(): Boolean = registry.currentService() != null

    override suspend fun findFirst(selector: ElementSelector): AccessibilityNodeSnapshot? {
        val service = registry.currentService() ?: return null
        val node = findFirstNode(service, selector) ?: return null
        node.recycle()
        return AccessibilityNodeSnapshot(nodeId = UUID.randomUUID().toString(), selector = selector)
    }

    override suspend fun click(node: AccessibilityNodeSnapshot): Boolean {
        val service = registry.currentService() ?: return false
        val selector = node.selector ?: return false
        val targetNode = findFirstNode(service, selector) ?: return false

        return performClick(targetNode)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        val traversedNodes = mutableListOf<AccessibilityNodeInfo>()
        var currentNode: AccessibilityNodeInfo? = node
        while (currentNode != null) {
            traversedNodes += currentNode
            if (currentNode.isClickable && currentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                traversedNodes.forEach(AccessibilityNodeInfo::recycle)
                return true
            }
            currentNode = currentNode.parent
        }

        traversedNodes.forEach(AccessibilityNodeInfo::recycle)
        return false
    }

    private fun findFirstNode(
        service: AccessibilityServiceHandle,
        selector: ElementSelector,
    ): AccessibilityNodeInfo? {
        val rootNode = service.currentRootNode() ?: return null

        return try {
            when (selector.by) {
                SelectorType.RESOURCE_ID -> rootNode.findByViewId(selector.value)
                SelectorType.TEXT -> rootNode.findByText(selector.value)
                SelectorType.CONTENT_DESCRIPTION -> rootNode.findByPredicate { it.contentDescription?.toString() == selector.value }
                SelectorType.CLASS_NAME -> rootNode.findByPredicate { it.className?.toString() == selector.value }
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun AccessibilityNodeInfo.findByViewId(viewId: String): AccessibilityNodeInfo? {
        val matches = findAccessibilityNodeInfosByViewId(viewId)
        return matches.consumeFirstMatch()
    }

    private fun AccessibilityNodeInfo.findByText(text: String): AccessibilityNodeInfo? {
        val matches = findAccessibilityNodeInfosByText(text)
        return matches.consumeFirstMatch()
    }

    private fun AccessibilityNodeInfo.findByPredicate(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(this)) {
            return AccessibilityNodeInfo.obtain(this)
        }

        for (index in 0 until childCount) {
            val childNode = getChild(index) ?: continue
            try {
                childNode.findByPredicate(predicate)?.let { return it }
            } finally {
                childNode.recycle()
            }
        }

        return null
    }

    private fun List<AccessibilityNodeInfo>.consumeFirstMatch(): AccessibilityNodeInfo? {
        if (isEmpty()) {
            return null
        }

        val firstMatch = AccessibilityNodeInfo.obtain(first())
        forEach(AccessibilityNodeInfo::recycle)
        return firstMatch
    }
}