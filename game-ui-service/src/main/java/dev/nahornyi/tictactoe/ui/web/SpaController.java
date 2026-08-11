package dev.nahornyi.tictactoe.ui.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React entry point for any path that is not an API call or a static asset, so that
 * deep links and reloads reach the SPA instead of a 404.
 *
 * <p>The {@code [^\.]*} pattern excludes anything with a file extension, which keeps real static
 * files (bundles, icons) being served by the resource handler.
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/", "/{path:[^\\.]*}"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
