package kr.hamkkejaram.child;

import jakarta.validation.Valid;
import kr.hamkkejaram.child.ChildDtos.ChildResponse;
import kr.hamkkejaram.child.ChildDtos.CreateChildRequest;
import kr.hamkkejaram.child.ChildDtos.UpdateChildRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/children")
public class ChildController {
    private final ChildService service;

    public ChildController(ChildService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChildResponse create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateChildRequest req) {
        return service.create(user, req);
    }

    @GetMapping
    public List<ChildResponse> list(@AuthenticationPrincipal AuthUser user) {
        return service.list(user);
    }

    @GetMapping("/{childId}")
    public ChildResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.get(user, childId);
    }

    @PatchMapping("/{childId}")
    public ChildResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                @Valid @RequestBody UpdateChildRequest req) {
        return service.update(user, childId, req);
    }

    @DeleteMapping("/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        service.delete(user, childId);
    }
}
