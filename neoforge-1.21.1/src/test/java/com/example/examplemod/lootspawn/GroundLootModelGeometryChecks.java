package com.example.examplemod.lootspawn;

import com.example.examplemod.lootspawn.client.GroundLootBonePolicy;
import com.example.examplemod.lootspawn.client.GroundLootFlatPose;
import com.example.examplemod.lootspawn.client.GroundLootQuadGeometry;
import java.util.Set;

/** Standalone checks: no Minecraft, TaCZ, assets, GPU or Gradle needed. */
public final class GroundLootModelGeometryChecks {
    private static int checks;
    public static void main(String[] args) {
        check(!GroundLootQuadGeometry.hasArea(new double[][] {{7,-50,8},{7,-50,8},{7,-50,8},{7,-50,8}}),
                "missing UV face at a far bone origin is never physical geometry");
        check(!GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0},{1,0,0},{2,0,0},{3,0,0}}), "line is not a face");
        check(GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0},{1,0,0},{1,0,1},{0,0,1}}), "horizontal plane remains drawable");
        check(GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0},{.001,0,0},{.001,.001,0},{0,.001,0}}), "small physical faces are retained");
        check(GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0},{0,0,0},{1,0,1},{0,0,1}}), "one valid triangle retains a quad");
        check(!GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0},{1,Double.NaN,0},{1,0,1},{0,0,1}}), "nonfinite vertex is rejected");
        check(!GroundLootQuadGeometry.hasArea(new double[][] {{0,0,0}}), "incomplete quad is rejected");
        var empty = GroundLootBonePolicy.Appearance.EMPTY;
        for (String hidden : new String[] {"mount","sight_folded","mag_extended_1","mag_extended_2","mag_extended_3",
                "handguard_tactical","additional_magazine","bullet_in_mag","bullet_in_barrel","bullet_chain",
                "lefthand","righthand","lefthand_pos","righthand_pos","muzzle_flash","laser_beam_1"}) {
            check(!GroundLootBonePolicy.gunVisible(hidden, "root", empty), "empty gun hides " + hidden);
        }
        for (String visible : new String[] {"root","body","sight","carry","mag_standard","handguard_default","stock_default","muzzle_default"}) {
            check(GroundLootBonePolicy.gunVisible(visible, "root", empty), "empty gun shows " + visible);
        }
        var equipped = new GroundLootBonePolicy.Appearance(true,true,2,true,true,true,
                Set.of("scope","grip","stock","muzzle","extended_mag"),Set.of("rail_adapter"),true);
        for (String visible : new String[] {"mount","sight_folded","mag_extended_2","handguard_tactical","bullet_in_mag","bullet_in_barrel","bullet_chain","muzzle_default"}) {
            check(GroundLootBonePolicy.gunVisible(visible,"root",equipped), "equipped gun shows " + visible);
        }
        for (String hidden : new String[] {"sight","carry","mag_standard","mag_extended_1","mag_extended_3","handguard_default","stock_default","grip_default","extended_mag_default"}) {
            check(!GroundLootBonePolicy.gunVisible(hidden,"root",equipped), "equipped gun hides " + hidden);
        }
        check(GroundLootBonePolicy.gunVisible("rail_adapter","attachment_adapter",equipped), "installed adapter retained");
        check(!GroundLootBonePolicy.gunVisible("unused_adapter","attachment_adapter",equipped), "unused adapter excluded");
        check(!GroundLootBonePolicy.gunVisible("rail_adapter","attachment_adapter",empty), "empty gun has no attachment adapters");
        for (String hidden : new String[] {"division","division_2","ocular","ocular_3","ocular_scope","ocular_scope_2","ocular_sight_8","laser_beam_2"}) {
            check(!GroundLootBonePolicy.supplementVisible(hidden), "nonphysical optic/effect node is excluded: " + hidden);
        }
        check(GroundLootBonePolicy.supplementVisible("ocular_ring"), "physical eyepiece ring is retained");
        check(GroundLootBonePolicy.supplementVisible("scope_body"), "physical optic body is retained");
        check(!GroundLootBonePolicy.supplementVisible("suffuse:sight_dbala2", "scope_body_illuminated"),
                "DBAL sight's pack-authored long laser rays are effects, not collision geometry");
        check(!GroundLootBonePolicy.supplementVisible("classicr:sight_canted_laser", "beam_illuminated"),
                "canted laser's emissive beam is excluded from drawing and measurement");
        for (String physical : new String[] {"scope_body", "dbal_a2", "mini_laser_v", "laser_dye", "bone43"}) {
            check(GroundLootBonePolicy.supplementVisible("suffuse:sight_dbala2", physical),
                    "real DBAL housing and geometry retained: " + physical);
            check(GroundLootBonePolicy.supplementVisible("classicr:sight_canted_laser", physical),
                    "real canted-laser housing and geometry retained: " + physical);
        }
        check(GroundLootBonePolicy.supplementVisible("other:legitimate_lamp", "scope_body_illuminated"),
                "a same-named real illuminated housing in another asset remains visible");
        check(GroundLootBonePolicy.supplementVisible("other:legitimate_lamp", "beam_illuminated"),
                "asset-specific filtering does not hide unrelated physical beam-shaped housings");
        check(!GroundLootBonePolicy.supplementVisible("SUFFUSE:SIGHT_DBALA2", "SCOPE_BODY_ILLUMINATED"),
                "asset-specific effects normalize case consistently with the original bone policy");
        // Reproduce the placement effect: a phantom pivot below a real flat mesh raises the real mesh.
        var bad = GroundLootFlatPose.fromBounds(-.6,-1.5,-.2,.6,.2,.2);
        var repaired = GroundLootFlatPose.fromBounds(-.6,.1,-.2,.6,.2,.2);
        check(repaired.xRotationDegrees()==0 && repaired.zRotationDegrees()==0, "real drawable mesh keeps its flat authored orientation");
        check(Math.abs(repaired.offsetY()+.1)<1e-12, "drawable mesh bottom is translated exactly onto floor");
        check(bad.xRotationDegrees()!=0 || Math.abs(bad.offsetY()-repaired.offsetY())>.01,
                "phantom bounds demonstrably change the old placement");
        System.out.println("Ground loot model geometry checks passed: " + checks);
    }
    private static void check(boolean ok,String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }
}
