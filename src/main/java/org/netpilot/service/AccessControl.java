package org.netpilot.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.netpilot.model.Role;
import org.netpilot.model.Role.Permission;

/**
 * Who is using NetPilot right now, and what that allows. Every privileged action asks
 * {@link #can} (or {@link #require}) when it runs, not only when its button is drawn, so a hidden
 * button, a keyboard shortcut or an old window left open cannot get around it.
 *
 * <p>Without app protection the user is always the owner. With it, NetPilot starts as the cashier
 * and becomes the owner only with the owner's PIN.
 */
public final class AccessControl {

    private static final System.Logger LOG = System.getLogger(AccessControl.class.getName());

    private final PinVault vault;
    private final List<Consumer<Role>> listeners = new CopyOnWriteArrayList<>();
    private volatile Role role;

    public AccessControl(PinVault vault) {
        this.vault = Objects.requireNonNull(vault);
        this.role = vault.enabled() ? Role.CASHIER : Role.OWNER;
    }

    public PinVault vault() {
        return vault;
    }

    public Role role() {
        return role;
    }

    public boolean can(Permission permission) {
        return role.can(permission);
    }

    /**
     * @throws SecurityException when the current role may not do this
     */
    public void require(Permission permission) {
        if (!can(permission)) {
            LOG.log(System.Logger.Level.WARNING, "Blocked {0} for the {1}", permission, role);
            throw new SecurityException("owner-only:" + permission);
        }
    }

    /**
     * Switches role. Becoming the owner while protection is on needs the owner's PIN first (checked
     * by the caller through {@link PinVault#check}); without protection the role is always the owner.
     */
    public void setRole(Role next) {
        Role effective = vault.enabled() ? Objects.requireNonNull(next) : Role.OWNER;
        if (effective == role) {
            return;
        }
        role = effective;
        LOG.log(System.Logger.Level.INFO, "Now used as the {0}", effective);
        listeners.forEach(l -> l.accept(effective));
    }

    /** Protection was turned on or off: the role follows. */
    public void protectionChanged() {
        Role before = role;
        setRole(vault.enabled() ? role : Role.OWNER);
        if (role == before) {
            listeners.forEach(l -> l.accept(role)); // the top bar shows or hides the role button
        }
    }

    public void addListener(Consumer<Role> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Role> listener) {
        listeners.remove(listener);
    }
}
