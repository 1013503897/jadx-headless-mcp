package jadx.api

import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode

/**
 * Package-local bridge into jadx's ClassNode→Java* converters.
 *
 * [JadxDecompiler.convertClassNode] / [convertMethodNode] / [convertFieldNode] are
 * package-private in jadx 1.5.x; they wrap nodes without [JavaClass.load]/decompile.
 * Living in `jadx.api` lets jadx-headless-mcp build metadata indexes without walking
 * [JavaClass.innerClasses]/[JavaClass.getMethods] (which call load()).
 */
internal object JhmcpJadxAccess {
    fun wrapClass(decompiler: JadxDecompiler, node: ClassNode): JavaClass =
        decompiler.convertClassNode(node)

    fun wrapMethod(decompiler: JadxDecompiler, node: MethodNode): JavaMethod =
        decompiler.convertMethodNode(node)

    fun wrapField(decompiler: JadxDecompiler, node: FieldNode): JavaField =
        decompiler.convertFieldNode(node)
}
