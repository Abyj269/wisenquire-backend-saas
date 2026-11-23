package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "whatsapp_saved_replies")
@Getter @Setter @NoArgsConstructor
public class SavedReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long companyId;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 4096)
    private String body;
    @Column(nullable = false)
    private boolean active = true;
}
