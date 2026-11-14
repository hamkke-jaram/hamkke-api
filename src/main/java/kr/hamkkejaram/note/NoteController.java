package kr.hamkkejaram.note;

import jakarta.validation.Valid;
import kr.hamkkejaram.note.NoteDtos.CreateGoalRequest;
import kr.hamkkejaram.note.NoteDtos.CreateObservationRequest;
import kr.hamkkejaram.note.NoteDtos.GoalResponse;
import kr.hamkkejaram.note.NoteDtos.ObservationResponse;
import kr.hamkkejaram.note.NoteDtos.UpdateGoalRequest;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/children/{childId}")
public class NoteController {
    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping("/goals")
    public List<GoalResponse> goals(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.listGoals(user, childId);
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse createGoal(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                   @Valid @RequestBody CreateGoalRequest req) {
        return service.createGoal(user, childId, req);
    }

    @PatchMapping("/goals/{goalId}")
    public GoalResponse updateGoal(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                   @PathVariable long goalId, @Valid @RequestBody UpdateGoalRequest req) {
        return service.updateGoal(user, childId, goalId, req);
    }

    @DeleteMapping("/goals/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                           @PathVariable long goalId) {
        service.deleteGoal(user, childId, goalId);
    }

    @GetMapping("/observations")
    public List<ObservationResponse> observations(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                                  @RequestParam(defaultValue = "50") int limit,
                                                  @RequestParam(required = false) Long beforeId) {
        return service.listNotes(user, childId, limit, beforeId);
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public ObservationResponse createObservation(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                                 @Valid @RequestBody CreateObservationRequest req) {
        return service.createNote(user, childId, req);
    }

    @DeleteMapping("/observations/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteObservation(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                  @PathVariable long noteId) {
        service.deleteNote(user, childId, noteId);
    }
}
