package org.baseplayer.variant;

public final class VariantDrawSeek {

  private static final long LARGE_JUMP_BP = 5_000_000L;

  private VariantList list;
  private int chainGeneration;
  private long seekViewStart;
  private VariantNode firstDrawNode;

  public VariantNode seek(VariantList list, long viewStart) {
    if (list == null) {
      clear();
      return null;
    }

    int generation = list.getVisibleChainGeneration();
    boolean stale = this.list != list
        || chainGeneration != generation
        || firstDrawNode == null;
    if (stale) {
      return reseed(list, viewStart, generation);
    }

    if (viewStart == seekViewStart) {
      return firstDrawNode;
    }

    long delta = Math.abs(viewStart - seekViewStart);
    if (delta > LARGE_JUMP_BP) {
      return reseed(list, viewStart, generation);
    }

    if (isFirstStillValid(firstDrawNode, viewStart)) {
      seekViewStart = viewStart;
      return firstDrawNode;
    }

    VariantNode from = firstDrawNode;
    VariantNode node = firstDrawNode;
    if (viewStart > from.position) {
      while (node != null && node.position < viewStart) {
        node = node.nextVisible;
      }
    } else {
      while (node != null
          && node.prevVisible != null
          && node.prevVisible.position >= viewStart) {
        node = node.prevVisible;
      }
    }


    seekViewStart = viewStart;
    firstDrawNode = node;
    return node;
  }

  private static boolean isFirstStillValid(VariantNode first, long viewStart) {
    if (first.position < viewStart) {
      return false;
    }
    VariantNode prev = first.prevVisible;
    return prev == null || prev.position < viewStart;
  }

  public void clear() {
    list = null;
    chainGeneration = 0;
    seekViewStart = 0;
    firstDrawNode = null;
  }

  private VariantNode reseed(VariantList list, long viewStart, int generation) {
    this.list = list;
    this.chainGeneration = generation;
    this.seekViewStart = viewStart;
    this.firstDrawNode = list.findFirstVisibleAtOrAfter(viewStart);
    return firstDrawNode;
  }
}