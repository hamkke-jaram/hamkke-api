package kr.hamkkejaram.record;

import jakarta.validation.Valid;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.record.RecordDtos.CreateRecordRequest;
import kr.hamkkejaram.record.RecordDtos.RecordResponse;
import kr.hamkkejaram.record.RecordDtos.SaveRecordResponse;
import kr.hamkkejaram.record.RecordDtos.WeeklySummaryResponse;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
public class RecordController {
    private final RecordService service;
    private final WeeklySummaryService weekly;
    private final ChildAccess access;

    public RecordController(RecordService service, WeeklySummaryService weekly, ChildAccess access) {
        this.service = service;
        this.weekly = weekly;
        this.access = access;
    }

    @PostMapping("/interaction-records")
    public ResponseEntity<SaveRecordResponse> create(@AuthenticationPrincipal AuthUser user,
                                                     @PathVariable long childId,
                                                     @Valid @RequestBody CreateRecordRequest req) {
        RecordService.SaveResult result = service.save(user, childId, req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/interaction-records")
    public List<RecordResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                     @RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(required = false) Long beforeId) {
        return service.list(user, childId, limit, beforeId);
    }

    @DeleteMapping("/interaction-records/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                       @PathVariable long recordId) {
        service.delete(user, childId, recordId);
    }

    @GetMapping("/weekly-summary")
    public WeeklySummaryResponse weeklySummary(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                               @RequestParam(defaultValue = "8") int weeks) {
        access.requireOwner(user, childId);
        return new WeeklySummaryResponse(weekly.summarize(childId, weeks));
    }
}
