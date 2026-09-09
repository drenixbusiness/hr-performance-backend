package uz.drenix.identity.user.domain;

/**
 * The operating companies a role assignment can be narrowed to.
 *
 * <p>Kept in step with Entity in common.proto and with the user_roles_entity_valid check
 * constraint. Adding a company means changing all three.
 */
public enum Entity4 {
    JM, BP
}
