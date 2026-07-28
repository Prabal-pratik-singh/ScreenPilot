package com.screenpilot.signage.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * JPA entity mapped to the "layout_zones" table. A row is one rectangular region inside a
 * {@link Layout}: its position/size (x, y, w, h are fractions of the screen, 0..1), its
 * stacking order (z), what kind of content it shows, and an optional playlist for MEDIA zones.
 */
@Entity
@Table(name = "layout_zones")
public class LayoutZone {

    /** What the zone renders: playlist media, scrolling ticker, widget (clock/weather), static logo, or a web page. */
    public enum Type { MEDIA, TICKER, WIDGET, LOGO, WEB }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "layout_id")
    private Layout layout;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(nullable = false)
    private double w;

    @Column(nullable = false)
    private double h;

    @Column(nullable = false)
    private int z = 1;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "playlist_id")
    private Playlist playlist;

    /** JSON blob per zone type: ticker text/colors/speed, widget kind, logo mediaId, web url. */
    @Column(name = "config")
    private String config;

    public LayoutZone() {
    }

    public LayoutZone(Layout layout, Type type) {
        this.id = UUID.randomUUID();
        this.layout = layout;
        this.type = type;
    }

    public UUID getId() { return id; }
    public Layout getLayout() { return layout; }
    public void setLayout(Layout layout) { this.layout = layout; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getW() { return w; }
    public void setW(double w) { this.w = w; }
    public double getH() { return h; }
    public void setH(double h) { this.h = h; }
    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }
    public Playlist getPlaylist() { return playlist; }
    public void setPlaylist(Playlist playlist) { this.playlist = playlist; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
}
