package redcon.desktop;

import o3.a1;
import o3.h1;
import o3.z0;
import redcon.duel.FortDesign;

/** Arms REDCON's shipped battle activity from two committed duel snapshots. */
public final class OriginalBattleScreen {

    public static a1 open(FortDesign nearDesign, FortDesign farDesign) throws Exception {
        if (nearDesign == null || farDesign == null) {
            throw new IllegalArgumentException("both committed forts are required");
        }
        z0 near = nearDesign.instantiate();
        z0 far = farDesign.instantiate();

        q3.b screen = q3.b.j0;
        o3.e1 mission = screen.d0;
        h1 nearLoadout = RecoveredGame.allocate(h1.class);
        nearLoadout.f = near;
        nearLoadout.b = Duel.TEAM_NEAR;
        h1 farLoadout = RecoveredGame.allocate(h1.class);
        farLoadout.f = far;
        farLoadout.b = Duel.TEAM_FAR;

        q3.v config = RecoveredGame.allocate(q3.v.class);
        config.f = near;
        config.i = far;
        mission.a = config;
        mission.b = nearLoadout;
        mission.c = farLoadout;
        mission.a(config);
        screen.M = config;

        screen.K();
        a1 battle = a1.Q0;
        battle.B();
        mojo.b.i.b = battle;
        Duel.ensureFramePacing();
        return battle;
    }

    private OriginalBattleScreen() {
    }
}
