package com.selectcode.pos.shared.files;

import org.springframework.data.jpa.repository.JpaRepository;

interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
}
