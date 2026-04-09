package io.routify.cert.messaging;

import io.routify.cert.dto.CertGroupDto;
import io.routify.cert.dto.CertificateDto;
import io.routify.cert.domain.AcmeAccount;
import io.routify.cert.domain.AcmeOrder;
import io.routify.cert.service.AcmeService;
import io.routify.cert.service.CertGroupService;
import io.routify.cert.service.CertificateVaultService;
import io.routify.cert.repository.AcmeAccountRepository;
import io.routify.cert.repository.AcmeOrderRepository;
import io.routify.common.event.QueryRequest;
import io.routify.common.event.QueryResponse;
import io.routify.common.event.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RabbitMQ request/reply handler for routify-cert-vault.
 *
 * <p>All request bodies are deserialised into strongly-typed {@link QueryRequest} records
 * by the Jackson2JsonMessageConverter in the listener container.
 * Return values are serialised back to JSON automatically by the same converter.
 *
 * <p>Write operations arrive via Kafka commands consumed by {@link CertCommandKafkaConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertVaultRabbitHandler {

    private final CertificateVaultService vaultService;
    private final CertGroupService        groupService;
    private final AcmeService             acmeService;
    private final AcmeAccountRepository   acmeAccountRepository;
    private final AcmeOrderRepository     acmeOrderRepository;

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_QUERY)
    public QueryResponse.CertsPage handleCertificatesQuery(QueryRequest.CertsQuery req) {
        log.debug("RabbitMQ: cert vault query received");
        String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
        String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

        var pageResult = vaultService.listCertificates(
                req.tenantId(), req.status(), req.page(), req.size(), sortBy, sortDir);
        var content = pageResult.getContent().stream().map(this::toCertSummary).toList();
        return new QueryResponse.CertsPage(content,
                pageResult.getTotalElements(), pageResult.getTotalPages(),
                pageResult.getNumber(), pageResult.getSize(),
                pageResult.isFirst(), pageResult.isLast());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_GET)
    public QueryResponse.CertDetail handleCertificateGet(QueryRequest.CertGet req) {
        log.debug("RabbitMQ: cert get request received");
        return toCertDetail(vaultService.getCertificate(req.id(), req.tenantId()));
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_ACTIVE_LIST)
    public QueryResponse.CertsList handleActiveCertsList(QueryRequest.CertsActiveList req) {
        log.debug("RabbitMQ: active certs list request received");
        var items = vaultService.listActiveCertificates(req.tenantId())
                .stream().map(this::toCertSummary).toList();
        return new QueryResponse.CertsList(items);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_STATS)
    public QueryResponse.CertStatsResult handleCertStats(QueryRequest.CertStats req) {
        log.debug("RabbitMQ: cert stats request received");
        var raw = vaultService.getStats(req.tenantId());
        long total        = raw.get("total") instanceof Number n ? n.longValue() : 0L;
        long active       = raw.get("active") instanceof Number n ? n.longValue() : 0L;
        long expiringSoon = raw.get("expiringSoon") instanceof Number n ? n.longValue() : 0L;
        @SuppressWarnings("unchecked")
        var counts = raw.get("counts") instanceof java.util.Map<?, ?> m
                ? (java.util.Map<String, Long>) m : java.util.Map.<String, Long>of();
        return new QueryResponse.CertStatsResult(total, active, expiringSoon, counts);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_GATEWAY_SNAPSHOT)
    public QueryResponse.CertsList handleGatewayMappedSnapshot(QueryRequest.CertsGatewaySnapshot req) {
        log.debug("RabbitMQ: gateway cert snapshot request received");
        var items = vaultService.listGatewayMappedCerts(req.tenantId())
                .stream().map(this::toCertSummary).toList();
        return new QueryResponse.CertsList(items);
    }

    /**
     * Serves decrypted certificate material (PEM chain + optional private key) to the gateway.
     * <strong>Internal only</strong> — this queue must not be accessible outside the service mesh.
     */
    @RabbitListener(queues = RabbitTopology.QUEUE_CERTS_FETCH_MATERIAL)
    public QueryResponse.GatewayConfig handleFetchMaterial(QueryRequest.CertFetchMaterial req) {
        log.debug("RabbitMQ: cert fetch-material request received");
        // fetchDecryptedMaterial returns Map<String, String> with "certPem" and optionally "privateKey"
        var material = vaultService.fetchDecryptedMaterial(req.id(), req.tenantId());
        return new QueryResponse.GatewayConfig(new java.util.LinkedHashMap<>(material));
    }

    // ─── Cert Group Query Handlers ────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_QUERY)
    public QueryResponse.CertGroupsPage handleCertGroupsQuery(QueryRequest.CertGroupsQuery req) {
        log.debug("RabbitMQ: cert-groups query received");
        String sortBy  = req.sortBy()  != null ? req.sortBy()  : "createdAt";
        String sortDir = req.sortDir() != null ? req.sortDir() : "DESC";

        var pageResult = groupService.listGroups(
                req.tenantId(), req.status(), req.page(), req.size(), sortBy, sortDir);
        var content = pageResult.getContent().stream().map(this::toCertGroupSummary).toList();
        return new QueryResponse.CertGroupsPage(content,
                pageResult.getTotalElements(), pageResult.getTotalPages(),
                pageResult.getNumber(), pageResult.getSize(),
                pageResult.isFirst(), pageResult.isLast());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_GET)
    public QueryResponse.CertGroupDetail handleCertGroupGet(QueryRequest.CertGroupGet req) {
        log.debug("RabbitMQ: cert-groups get received");
        return toCertGroupDetail(groupService.getGroup(req.id(), req.tenantId()));
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_CERT_GROUPS_MEMBERS)
    public QueryResponse.CertGroupMembersList handleCertGroupMembers(QueryRequest.CertGroupMembers req) {
        log.debug("RabbitMQ: cert-groups members query received");
        List<QueryResponse.CertSummary> members = groupService
                .listGroupMembers(req.groupId(), req.tenantId())
                .stream().map(this::toCertSummary).toList();
        return new QueryResponse.CertGroupMembersList(members);
    }

    // ─── ACME Query Handlers ──────────────────────────────────────────────────

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_REGISTER)
    public QueryResponse.AcmeAccountResult handleAcmeRegister(QueryRequest.AcmeRegister req) {
        log.debug("RabbitMQ: ACME register request received");
        AcmeAccount account = acmeService.registerAccount(
                req.tenantId(), req.email(),
                AcmeAccount.AcmeProvider.valueOf(req.provider()));
        return toAcmeAccountResult(account);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_ISSUE)
    public QueryResponse.AcmeOrderDetail handleAcmeIssue(QueryRequest.AcmeIssue req) {
        log.debug("RabbitMQ: ACME issue request received");
        AcmeOrder order = acmeService.requestCertificate(
                req.accountId(), req.domain(), req.certGroupId(), req.tenantId());
        return toAcmeOrderDetail(order);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_ORDERS_QUERY)
    public QueryResponse.AcmeOrdersPage handleAcmeOrdersQuery(QueryRequest.AcmeOrdersQuery req) {
        log.debug("RabbitMQ: ACME orders query received");
        var pageResult = acmeOrderRepository.findByTenantId(
                req.tenantId(), PageRequest.of(req.page(), req.size()));
        var content = pageResult.getContent().stream().map(this::toAcmeOrderDetail).toList();
        return new QueryResponse.AcmeOrdersPage(content,
                pageResult.getTotalElements(), pageResult.getTotalPages(),
                pageResult.getNumber(), pageResult.getSize(),
                pageResult.isFirst(), pageResult.isLast());
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_ORDER_GET)
    public QueryResponse.AcmeOrderDetail handleAcmeOrderGet(QueryRequest.AcmeOrderGet req) {
        log.debug("RabbitMQ: ACME order get received");
        AcmeOrder order = acmeOrderRepository.findByIdAndTenantId(req.id(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AcmeOrder", req.id().toString()));
        return toAcmeOrderDetail(order);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_RENEW)
    public QueryResponse.AcmeOrderDetail handleAcmeRenew(QueryRequest.AcmeRenew req) {
        log.debug("RabbitMQ: ACME renew request received");
        acmeService.renewCertificate(req.orderId());
        AcmeOrder order = acmeOrderRepository.findByIdAndTenantId(req.orderId(), req.tenantId())
                .orElseThrow(() -> new io.routify.common.exception.RoutifyException.NotFound(
                        "AcmeOrder", req.orderId().toString()));
        return toAcmeOrderDetail(order);
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_ACME_ACCOUNTS_QUERY)
    public QueryResponse.AcmeAccountsList handleAcmeAccountsQuery(QueryRequest.AcmeAccountsQuery req) {
        log.debug("RabbitMQ: ACME accounts query received for tenantId={}", req.tenantId());
        var accounts = acmeAccountRepository.findByTenantId(req.tenantId()).stream()
                .map(this::toAcmeAccountResult)
                .toList();
        return new QueryResponse.AcmeAccountsList(accounts);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private QueryResponse.CertSummary toCertSummary(CertificateDto c) {
        return new QueryResponse.CertSummary(
                c.getId(), c.getTenantId(), c.getLogicalId(), c.getAlias(),
                c.getDescription(), c.getFormat(), c.getStatus(), c.getExpiryStatus(),
                c.getSubjectDn(), c.getIssuerDn(), c.getSerialNumber(),
                c.getNotBefore(), c.getNotAfter(), c.getSignatureAlg(),
                c.getKeyAlgorithm(), c.getKeySize(),
                c.getFingerprintSha1(), c.getFingerprintSha256(),
                c.getSanDns(), c.getSanIp(), c.isCa(), c.isHasPrivateKey(),
                c.getGatewayTlsLogicalId(), c.getGroupId(), c.getGroupLogicalId(),
                c.getMemberAlias(), c.getEffectiveGatewayLogicalId(),
                c.getUploadedBy(), c.getCreatedAt(), c.getUpdatedAt(), c.getExpiresAt());
    }

    private QueryResponse.CertDetail toCertDetail(CertificateDto c) {
        return new QueryResponse.CertDetail(
                c.getId(), c.getTenantId(), c.getLogicalId(), c.getAlias(),
                c.getDescription(), c.getFormat(), c.getStatus(), c.getExpiryStatus(),
                c.getSubjectDn(), c.getIssuerDn(), c.getSerialNumber(),
                c.getNotBefore(), c.getNotAfter(), c.getSignatureAlg(),
                c.getKeyAlgorithm(), c.getKeySize(),
                c.getFingerprintSha1(), c.getFingerprintSha256(),
                c.getSanDns(), c.getSanIp(), c.isCa(), c.isHasPrivateKey(),
                c.getGatewayTlsLogicalId(), c.getGroupId(), c.getGroupLogicalId(),
                c.getMemberAlias(), c.getEffectiveGatewayLogicalId(),
                c.getUploadedBy(), c.getCreatedAt(), c.getUpdatedAt(), c.getExpiresAt());
    }

    private QueryResponse.CertGroupSummary toCertGroupSummary(CertGroupDto g) {
        return new QueryResponse.CertGroupSummary(
                g.getId(), g.getTenantId(), g.getLogicalId(), g.getAlias(),
                g.getDescription(), g.getStatus(), g.getMemberCount(),
                g.getExpiryHealthStatus(), g.getCreatedBy(), g.getCreatedAt(), g.getUpdatedAt());
    }

    private QueryResponse.CertGroupDetail toCertGroupDetail(CertGroupDto g) {
        List<QueryResponse.CertSummary> members = g.getMembers() != null
                ? g.getMembers().stream().map(this::toCertSummary).toList()
                : List.of();
        return new QueryResponse.CertGroupDetail(
                g.getId(), g.getTenantId(), g.getLogicalId(), g.getAlias(),
                g.getDescription(), g.getStatus(), g.getMemberCount(),
                g.getExpiryHealthStatus(), members,
                g.getCreatedBy(), g.getCreatedAt(), g.getUpdatedAt());
    }

    private QueryResponse.AcmeAccountResult toAcmeAccountResult(AcmeAccount a) {
        return new QueryResponse.AcmeAccountResult(
                a.getId(), a.getTenantId(), a.getEmail(), a.getAccountUrl(),
                a.getProvider().name(), a.getStatus().name(), a.getCreatedAt());
    }

    private QueryResponse.AcmeOrderDetail toAcmeOrderDetail(AcmeOrder o) {
        return new QueryResponse.AcmeOrderDetail(
                o.getId(), o.getTenantId(), o.getDomain(), o.getCertGroupId(),
                o.getChallengeType().name(), o.getStatus().name(), o.getOrderUrl(),
                o.getChallengeToken(), o.getCertId(), o.isAutoRenew(),
                o.getLastRenewedAt(), o.getNextRenewalAt(), o.getErrorMessage(),
                o.getCreatedAt(), o.getUpdatedAt());
    }
}
