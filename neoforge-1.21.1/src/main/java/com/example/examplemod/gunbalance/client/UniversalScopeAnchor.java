package com.example.examplemod.gunbalance.client;

import java.util.ArrayList;
import java.util.List;

import com.tacz.guns.client.model.bedrock.BedrockPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** A detached positioning node: it never modifies the authored gun skeleton or animation data. */
public final class UniversalScopeAnchor {
    private UniversalScopeAnchor() {}

    public static List<BedrockPart> create(List<BedrockPart> ironView, List<BedrockPart> muzzle) {
        if (ironView == null || ironView.isEmpty()) return null;
        double distance = Double.NaN;
        if (muzzle != null && !muzzle.isEmpty()) {
            distance = authoredPosition(ironView).distance(authoredPosition(muzzle)) * 16.0;
        }
        List<BedrockPart> path = new ArrayList<>(ironView);
        path.add(new MountNode(ironView.getLast(), ScopeAnchorGeometry.forwardOffsetPixels(distance)));
        return List.copyOf(path);
    }

    private static Vector3f authoredPosition(List<BedrockPart> path) {
        Matrix4f matrix = new Matrix4f();
        for (BedrockPart part : path) {
            matrix.translate(part.x / 16.0f, part.y / 16.0f, part.z / 16.0f)
                    .rotateZ(part.zRot).rotateY(part.yRot).rotateX(part.xRot);
        }
        return matrix.transformPosition(new Vector3f());
    }

    private static final class MountNode extends BedrockPart {
        private MountNode(BedrockPart iron, float forwardPixels) {
            super("tacz_pubg_virtual_scope_pos");
            // A parent is required by TaCZ's inverse camera transform. Do not add this node to
            // iron.children: the native scope renderer traverses the returned path explicitly.
            parent = iron;
            setPos(0, -ScopeAnchorGeometry.VERTICAL_CLEARANCE_PIXELS, -forwardPixels);
        }
    }
}
