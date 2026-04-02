package gr.routify.cert.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import gr.routify.cert.domain.CertGroup;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing a {@link CertGroup}.
 *
 * <p>The group's {@code logicalId} is the key used by the gateway TLS registry and
 * the {@code CertRotation} / {@code CertVaultAuth} filters. Individual member certificates
 * are included in the {@code members} list (summary only, no raw material).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CertGroupDto {

    private UUID   id;
    private UUID   tenantId;

    /** Stable gateway TLS registry key — this is what filters and gateway config bind to. */
    private String logicalId;

    private String alias;
    private String description;

    /** ACTIVE | ARCHIVED */
    private String status;

    /** Summary: how many members this group contains */
    private int memberCount;

    /**
     * Expiry health roll-up derived from member certificates.
     * VALID | EXPIRING_SOON | EXPIRED — worst case across active members.
     */
    private String expiryHealthStatus;

    /**
     * Full member list — populated only on detail GET, not in list responses.
     * Each entry contains metadata fields only (no raw PEM material).
     */
    private List<CertificateDto> members;

    // ─── Audit ───────────────────────────────────────────────────────────────

    private String  createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    // ─── Factory ─────────────────────────────────────────────────────────────

    /** Lightweight list DTO — members not included. */
    public static CertGroupDto fromSummary(CertGroup g) {
        return CertGroupDto.builder()
                .id(g.getId())
                .tenantId(g.getTenantId())
                .logicalId(g.getLogicalId())
                .alias(g.getAlias())
                .description(g.getDescription())
                .status(g.getStatus() != null ? g.getStatus().name() : null)
                .memberCount(g.getMembers() != null ? g.getMembers().size() : 0)
                .expiryHealthStatus(computeExpiryHealth(g))
                .createdBy(g.getCreatedBy())
                .createdAt(g.getCreatedAt())
                .updatedAt(g.getUpdatedAt())
                .build();
    }

    /** Detailed DTO — includes member certificate list. */
    public static CertGroupDto fromDetail(CertGroup g) {
        var dto = fromSummary(g);
        if (g.getMembers() != null) {
            dto.setMembers(g.getMembers().stream().map(CertificateDto::from).toList());
        }
        return dto;
    }

    private static String computeExpiryHealth(CertGroup g) {
        if (g.getMembers() == null || g.getMembers().isEmpty()) return "VALID";
        // Worst-case across all active members
        boolean hasExpired      = false;
        boolean hasExpiringSoon = false;
        for (var m : g.getMembers()) {
            if (m.getStatus() == gr.routify.cert.domain.StoredCertificate.CertStatus.ACTIVE) {
                String s = m.computeExpiryStatus(30);
                if ("EXPIRED".equals(s))       hasExpired      = true;
                if ("EXPIRING_SOON".equals(s)) hasExpiringSoon = true;
            }
        }
        if (hasExpired)      return "EXPIRED";
        if (hasExpiringSoon) return "EXPIRING_SOON";
        return "VALID";
    }
}

