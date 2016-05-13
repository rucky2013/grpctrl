package com.grpctrl.rest.resource.auth;

import com.github.scribejava.core.oauth.OAuth20Service;
import com.grpctrl.common.supplier.OAuth20ServiceSupplier;

import java.net.URI;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("/auth/login")
public class Login {
    @Nonnull
    private final OAuth20ServiceSupplier oAuth20ServiceSupplier;

    @Inject
    public Login(@Nonnull final OAuth20ServiceSupplier oAuth20ServiceSupplier) {
        this.oAuth20ServiceSupplier = Objects.requireNonNull(oAuth20ServiceSupplier);
    }

    @GET
    public Response login() {
        final OAuth20Service oAuthService = this.oAuth20ServiceSupplier.get();
        return Response.seeOther(URI.create(oAuthService.getAuthorizationUrl())).build();
    }
}
