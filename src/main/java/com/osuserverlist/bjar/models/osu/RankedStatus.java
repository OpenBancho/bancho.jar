package com.osuserverlist.bjar.models.osu;

import java.util.Arrays;

public enum RankedStatus {
   UNSPECIFIED(-3),
   Graveyard(-2),
   WIP(-1),
   Pending(0),
   Ranked(1),
   Approved(2),
   Qualified(3),
   Loved(4);

   private final int id;

   private RankedStatus(int id) {
      this.id = id;
   }

   public int getId() {
      return this.id;
   }

   public static RankedStatus getById(int id) {
      return (RankedStatus) Arrays.stream(values())
            .filter(s -> s.id == id)
            .findFirst()
            .orElse(UNSPECIFIED);
   }

   public static RankedStatus fromOsuDirect(int osuDirectStatus) {
        return switch (osuDirectStatus) {
            case 0 -> Ranked;
            case 2 -> Pending;
            case 3 -> Qualified;
            case 5 -> Pending; // Graveyard
            case 7 -> Ranked;  // Played before
            case 8 -> Loved;
            default -> UNSPECIFIED; // equivalent to UpdateAvailable fallback
        };
    }
}
