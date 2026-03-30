package com.vaultvibes.backend.stokvels;

import com.vaultvibes.backend.stokvels.dto.CreateStokvelDTO;
import com.vaultvibes.backend.stokvels.dto.StokvelDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StokvelsService {

    private final StokvelsRepository stokvelsRepository;

    public List<StokvelDTO> listAll() {
        return stokvelsRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toDTO)
                .toList();
    }

    public StokvelDTO getById(UUID id) {
        return toDTO(findOrThrow(id));
    }

    @Transactional
    public StokvelDTO create(CreateStokvelDTO request) {
        if (stokvelsRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("A stokvel with the name '" + request.name() + "' already exists.");
        }
        StokvelsEntity entity = new StokvelsEntity();
        entity.setName(request.name());
        entity.setDescription(request.description());
        StokvelsEntity saved = stokvelsRepository.save(entity);
        log.info("STOKVEL_CREATED: id={} name={}", saved.getId(), saved.getName());
        return toDTO(saved);
    }

    @Transactional
    public StokvelDTO update(UUID id, String name, String description) {
        StokvelsEntity entity = findOrThrow(id);
        if (name != null && !name.isBlank()) entity.setName(name);
        if (description != null) entity.setDescription(description);
        log.info("STOKVEL_UPDATED: id={} name={}", id, entity.getName());
        return toDTO(stokvelsRepository.save(entity));
    }

    @Transactional
    public StokvelDTO setStatus(UUID id, String status) {
        StokvelsEntity entity = findOrThrow(id);
        entity.setStatus(status.toUpperCase());
        log.info("STOKVEL_STATUS_CHANGED: id={} status={}", id, status);
        return toDTO(stokvelsRepository.save(entity));
    }

    public String getNameById(UUID id) {
        if (id == null) return "Unknown";
        return stokvelsRepository.findById(id)
                .map(StokvelsEntity::getName)
                .orElse("Unknown");
    }

    public StokvelDTO toDTO(StokvelsEntity entity) {
        return new StokvelDTO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    private StokvelsEntity findOrThrow(UUID id) {
        return stokvelsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stokvel not found: " + id));
    }
}
