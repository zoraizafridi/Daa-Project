package pk.ooad.ntd.response;

import pk.ooad.ntd.domain.AnalystTeam;
import pk.ooad.ntd.domain.AssignmentResult;
import pk.ooad.ntd.domain.MitigationTask;

import java.util.List;

public interface AssignmentSolver {
    AssignmentResult planDaa(List<MitigationTask> tasks, List<AnalystTeam> teams);
}
