package pentos.g9;

import pentos.sim.Cell;
import pentos.sim.Building;
import pentos.sim.Land;
import pentos.sim.Move;

import java.util.*;

public class Player implements pentos.sim.Player {

    private int BASE_RESIDENCE_SCORE = 50; // base score for a residence
    private int PACKING_FACTOR_MULTIPLE = 10; // score multiple for each adjacent cell
    private int POND_BONUS_SCORE = 20; // score to add for a pond
    private int FIELD_BONUS_SCORE = 20; // score to add for a field
    private int BLOCK_SIZE = 8; // spacing between roads
    
    private Set<Cell> road_cells;
    private Random gen = new Random();
    private int resAreaCount = 1;

    /* (Move, score) tuple
     */
    class ScoredMove implements Comparable<ScoredMove> {
        public Move move;
        public int score;

        public ScoredMove(Move move, int score) {
            this.move = move;
            this.score = score;
        }

        public boolean equals(ScoredMove m) {
            return move == m.move && score == m.score;
        }

        public int compareTo(ScoredMove m) {
            return score - m.score;
        }
    }

    public void init() {
        road_cells = new HashSet<Cell>();
    }

    public Move getMoveIfValid(Building request, Land land, int i, int j, int ri) {
        Cell p = new Cell(i, j);
        Building b = request.rotations()[ri];

        if (land.buildable(b, p)) {
            Move chosen = new Move(true, request, p, ri, new HashSet<Cell>(),
                                   new HashSet<Cell>(), new HashSet<Cell>());

            Set<Cell> shiftedCells = new HashSet<Cell>();
            for (Cell x : chosen.request.rotations()[chosen.rotation])
                shiftedCells.add(new Cell(x.i+chosen.location.i,x.j+chosen.location.j));

            Set<Cell> roadCells = findShortestRoad(shiftedCells, land);

            if (roadCells != null) {
                road_cells.addAll(roadCells);
                chosen.road = roadCells;

                if (request.type == Building.Type.RESIDENCE) {
                    Set<Cell> markedForConstruction = new HashSet<Cell>();
                    markedForConstruction.addAll(roadCells);
                    chosen.water = randomWalk(shiftedCells, markedForConstruction, land, 4);
                    markedForConstruction.addAll(chosen.water);
                    chosen.park = randomWalk(shiftedCells, markedForConstruction, land, 4);
                }

                return chosen;
            }
        }

        return null;
    }


    /*
      Returns number of adjacent empty cells (how well packed the building is)
     */
    public int getPackingFactor(Building b, Cell position, Land land) {
        Set<Cell> emptyNeighbors = new HashSet<Cell>();
        Set<Cell> absBuildingCells = new HashSet<Cell>();
        
        for (Cell c : b) {
            Cell abs = new Cell(c.i + position.i, c.j + position.j);
            absBuildingCells.add(abs);
        }

        for (Cell abs : absBuildingCells) {
            Cell[] absNeighbors = abs.neighbors();
            for (Cell n : absNeighbors) {
                if (land.unoccupied(n)) {
                    // check if that cell on the land WOULD be occupied by this building
                    boolean occupied = false;
                    for (Cell d : absBuildingCells) {
                        if (d.equals(abs))
                            continue; // neighbor is next to the cell we used to get this neighbor, ignore!
                        if (n.equals(d)) {
                            occupied = true;
                        }
                    }
                    if (occupied == false) {
                        emptyNeighbors.add(n);
                    }
                }
            }
        }
        return emptyNeighbors.size() * PACKING_FACTOR_MULTIPLE;
    }

    /* Checks if building to be placed is adjacent to a pond
     */
    public boolean adjacentPond(Building b, Cell position, Land land) {
        Set<Cell> adjacentPoints = new HashSet<Cell>();
        for (Cell c : b) {
            Cell abs = new Cell(c.i + position.i, c.j + position.j);
            Cell[] adj = abs.neighbors();
            for (Cell a : adj) {
                adjacentPoints.add(a);
            }
        }

        for (Cell p : adjacentPoints) {
            if (land.isPond(p)) {
                return true;
            }
        }
        return false;
    }

    /* Checks if building to be placed is adjacent to a field
     */
    public boolean adjacentField(Building b, Cell position, Land land) {
        Set<Cell> adjacentPoints = new HashSet<Cell>();
        for (Cell c : b) {
            Cell abs = new Cell(c.i + position.i, c.j + position.j);
            Cell[] adj = abs.neighbors();
            for (Cell a : adj) {
                adjacentPoints.add(a);
            }
        }

        for (Cell p : adjacentPoints) {
            if (land.isField(p)) {
                return true;
            }
        }
        return false;
    }

    /*
      Checks if cell is occupied currently or will be by the building about to be placed
      c is an ABSOLUTE POSITION on the land
     */
    public boolean willBeUnoccupied(Cell c, Building b, Cell buildingPos, Land land) {
        if (!land.unoccupied(c)) {
            return false; // it IS occupied
        }

        for (Cell buildingCell : b) {
            Cell buildingCellAbs = new Cell(buildingCell.i + buildingPos.i, buildingCell.j + buildingPos.j);
            if (buildingCellAbs.equals(c)) {
                return false; // it WILL BE occupied by the building about to be placed
            }
        }

        // This is a cell that will eventually be occupied by a road
        if ((c.i + 1) % (BLOCK_SIZE + 1) == 0)
            return false;

        return true; // will be unoccupied
    }

    public Double getDist(Cell a, Cell b) {
        return Math.hypot(a.i - b.i, a.j - b.j);
    }

    /*
      Adds either a park or a field to a Move - we use the word "development" to refer to either
      of these entities.
     */
    public Move buildWithDevelopment(Cell buildingPos, Building request, int rotation,
                                     Set<Cell> roadCells, Land land, boolean hasPond,
                                     boolean hasField) {
        // Create a set that holds the cells that the building will occupy
        Set<Cell> shiftedCells = new HashSet<Cell>();
        Building b = request.rotations()[rotation];
        for (Cell cell : b) {
            shiftedCells.add(new Cell(cell.i + buildingPos.i, cell.j + buildingPos.j));
        }

        // Create a set of cells for the new development
        Set<Cell> developmentCells = new HashSet<Cell>();

        // TODO: Right now we're just selecting the last valid candidate development and building
        // it. We probably want to store each valid candidate in a list and score them so that
        // we can choose the best candidate instead.
        for (Cell cell : shiftedCells) {
            Cell[] adj = cell.neighbors();
            // Loop through all cells neighboring the building
            for (Cell neighbor : adj) {
                int i = neighbor.i;
                int j = neighbor.j;

                // Only build a development in the middle of a res area
                if (((i-3) % (BLOCK_SIZE + 1) != 0) && ((i-4) % (BLOCK_SIZE + 1) != 0))
                    continue;

                // Attempt to construct a horizontal development
                Set<Cell> candidateDevelopment = new HashSet<Cell>();
                boolean candidateIsValid = true;

                if (j+3 >= land.side)
                    candidateIsValid = false;

                for (int dj = 0; candidateIsValid && dj < 4; dj++) {
                    // Starting from a cell that neighbors a building, build to the right
                    Cell candidateCell = new Cell(i, j+dj);
                    // If the current cell is being used for something else, this candidate
                    // is invalid
                    if (!willBeUnoccupied(candidateCell, b, buildingPos, land) ||
                        roadCells.contains(candidateCell)) {
                        candidateIsValid = false;
                        break;
                    }
                    candidateDevelopment.add(candidateCell);
                }

                // If we couldn't construct a development by building rightward, build leftward
                // instead
                if (!candidateIsValid) {
                    candidateIsValid = true;
                    candidateDevelopment = new HashSet<Cell>();

                    if (j-3 < 0)
                        candidateIsValid = false;

                    for (int dj = 0; candidateIsValid && dj < 4; dj++) {
                        // Build to the left
                        Cell candidateCell = new Cell(i, j-dj);

                        // If the current cell is being used for something else, this candidate
                        // is invalid
                        if (!willBeUnoccupied(candidateCell, b, buildingPos, land) ||
                            roadCells.contains(candidateCell)) {
                            candidateIsValid = false;
                            break;
                        }
                        candidateDevelopment.add(candidateCell);
                    }
                }

                // If we found a valid candidate this round, mark it as the development to be
                // built
                if (candidateIsValid) {
                    developmentCells = candidateDevelopment;
                }
            }
        }

        // If the building doesn't have a pond or field, choose one randomly and build it
        if (!hasPond && !hasField) {
            int choice = gen.nextInt(2);
            if (choice == 0) {
                return new Move(true, request, buildingPos, rotation, roadCells,
                                new HashSet<Cell>(), developmentCells);
            } else {
                return new Move(true, request, buildingPos, rotation, roadCells, developmentCells,
                                new HashSet<Cell>());
            }
        } else if (hasPond) {
            // Build a field if there is a pond
            return new Move(true, request, buildingPos, rotation, roadCells, new HashSet<Cell>(),
                            developmentCells);
        } else {
            // Build a pond if there is a field
            return new Move(true, request, buildingPos, rotation, roadCells, developmentCells,
                            new HashSet<Cell>());
        }
    }

    /* Checks if building to be placed will be connected to a road
       (either already on the board or a part of the roads cells passed in
       as an argument) or not
     */
    public boolean hasRoadConnection(Building b, Cell buildingPosition, Land land, Set<Cell> roadCells) {
        Set<Cell> absBuildingCells = new HashSet<Cell>();
        
        for (Cell c : b) {
            Cell abs = new Cell(c.i + buildingPosition.i, c.j + buildingPosition.j);
            absBuildingCells.add(abs);
        }

        for (Cell abs : absBuildingCells) {
            Cell[] absNeighbors = abs.neighbors();
            for (Cell n : absNeighbors) {
                if (n.isRoad())
                    return true;
                if (roadCells.contains(n))
                    return true;
                if (isPerimeter(land, n))
                    return true;
            }
        }
        return false;
    }

    /* for a resArea, column, building, fills potentialMoves with possible 
       moves and the "score" assigned to each
     */
    private void evaluateMovesAt(int currResArea, int j, Building request,
                                Land land, Set<Cell> roadCells,
                                Vector<ScoredMove> potentialMoves) {
        // evaluate each rotation in this build spot
        for (int r = 0; r < request.rotations().length; r++) {
            Building b = request.rotations()[r];

            // ignore rotations that are greater than width 3
            int maxWidth = 0;
            for (Cell p : b) {
                if (p.j > maxWidth) {
                    maxWidth = p.j;
                }
            }
            if (maxWidth >= 3) {
                continue;
            }
            
            // ignore rotations that are less than height 3
            int maxHeight = 0;
            for (Cell p : b) {
                if (p.i > maxHeight) {
                    maxHeight = p.i;
                }
            }
            if (maxHeight < 2) {
                continue;
            }

            // get the row from res area number
            int i;
            if (currResArea % 2 == 0) {
                i = (currResArea / 2) * (BLOCK_SIZE + 1);
            } else {
                i = ((currResArea + 1) / 2) * (BLOCK_SIZE + 1) - 2 - (maxHeight);
            }
            Cell buildPosition = new Cell(i, j);
            
            // generate score for this rotation and add to potential moves
            if (land.buildable(b, buildPosition)
                && hasRoadConnection(b, buildPosition, land, roadCells)) {
                int score = BASE_RESIDENCE_SCORE;
                boolean hasPond = false;
                boolean hasField = false;

                // subtracts from the score the number of empty cells adjacent to the building
                // so better packed positions get higher scores
                score -= getPackingFactor(b, buildPosition, land);

                // increase score if ponds or field adjacent
                if (adjacentPond(b, buildPosition, land)) {
                    hasPond = true;
                    score += POND_BONUS_SCORE;
                }
                if (adjacentField(b, buildPosition, land)) {
                    hasField = true;
                    score += FIELD_BONUS_SCORE;
                }

                // add to the potentialMoves the base move without adding any fields or ponds
                Move potential = new Move(true, request, buildPosition, r, roadCells,
                                          new HashSet<Cell>(), new HashSet<Cell>());
                ScoredMove sMove = new ScoredMove(potential, score);
                potentialMoves.add(sMove);

                // TODO: The player will currently always give moves that include ponds and fields
                // a higher score. We might want to add a heuristic that subtracts points for the
                // space that ponds/fields occupy (in some cases, particularly in the end game, it
                // might be better not to insert a pond or field)
                if (!hasPond || !hasField) {
                    Move potentialPlus = buildWithDevelopment(buildPosition, request, r, roadCells,
                                                              land, hasPond, hasField);
                    score += POND_BONUS_SCORE;
                    ScoredMove sMovePlus = new ScoredMove(potentialPlus, score);
                    potentialMoves.add(sMovePlus);
                }
            } // end if buildable
        } // end building rotations for loop

    } 
    
    /* For residences, divide up the board into areas by spliting the board with roads
       that are BLOCK_SIZE cells apart, and each side of a road is a "res area":
       ================ PERIMETER ================================
       res area 0
       
       res area 1
       ================ ROAD ================================
       res area 2

       res area 3
       ================ ROAD ================================
       res area 4
       ...

     */
    public Move play(Building request, Land land) {
        int maxResAreas = land.side / (BLOCK_SIZE + 1) + 1; // BLOCK_SIZE empty cell + 1 road = BLOCK_SIZE + 1
        Vector<ScoredMove> potentialMoves = new Vector<ScoredMove>();
        System.out.println("Request type: " + request.type + " " + request.toString());
        if (request.type == Building.Type.RESIDENCE) {
            HashSet<Cell> roadCells = new HashSet<Cell>();
            
            for (int currResArea = 0; currResArea < resAreaCount; currResArea++) {
                for (int j = 0; j < land.side; j++) {
                    evaluateMovesAt(currResArea, j, request, land, roadCells, potentialMoves);
                }
            }

            // if no moves found, make a new res area
            if (potentialMoves.size() == 0) {
                int roadI = ( (resAreaCount+1)/2 ) * (BLOCK_SIZE + 1) - 1;
                for (int roadJ = 0; roadJ < land.side; roadJ++) {
                    Cell roadCell = new Cell(roadI, roadJ);
                    if (land.unoccupied(roadCell)) {
                        roadCells.add(roadCell);
                    } else {
                        // if the horizontal road is broken, don't try to build road
                        roadCells = new HashSet<Cell>();
                        break;
                    }
                }
                if (roadCells.size() > 0) {
                    resAreaCount += 2;
                    for (int currResArea = 0; currResArea < resAreaCount; currResArea++) {
                        for (int j = 0; j < land.side; j++) {
                            evaluateMovesAt(currResArea, j, request, land, roadCells, potentialMoves);
                        }
                    }
                }
            } // end if no moves found, make new res area

            // if could not make moves from above strategy, revert to default behavior
            if (potentialMoves.size() == 0) {
                for (int i = 0 ; i < land.side ; i++) {
                    for (int j = 0 ; j < land.side ; j++) {
                        for (int ri = 0 ; ri < request.rotations().length ; ri++) {
                            Move chosen = getMoveIfValid(request, land, i, j, ri);
                            if (chosen != null) {
                                return chosen;
                            }
                        }
                    }
                }
                return new Move(false); // default player failed to find a spot
            } else {
                // get the move with highest score from Vector potentialMoves
                Collections.sort(potentialMoves);
                ScoredMove bestScoredMove = potentialMoves.lastElement();
                Move bestMove = bestScoredMove.move;
                bestMove.road = roadCells;
                return bestMove;
            }
        } // end if request.type == RESIDENCE
        else if (request.type == Building.Type.FACTORY) {
            for (int i = land.side - 1 ; i >= 0; i--) {
                for (int j = land.side - 1 ; j >= 0; j--) {
                    for (int ri = 0 ; ri < request.rotations().length ; ri++) {
                        Move chosen = getMoveIfValid(request, land, i, j, ri);
                        if (chosen != null) {
                            return chosen;
                        }
                    }
                }
            }
        }

        return new Move(false);
    } // end play()
    
    private boolean isPerimeter(Land land, Cell cell) {
        int i = cell.i;
        int j = cell.j;

        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di != 0 && dj != 0 && !land.unoccupied(i+di, j+dj)) {
                    return true;
                }
            }
        }

        return false;
    }


    private Set<Cell> findShortestRoad(Set<Cell> b, Land land) {
        Set<Cell> output = new HashSet<Cell>();
        boolean[][] checked = new boolean[land.side][land.side];
        Queue<Cell> queue = new LinkedList<Cell>();

        Cell source = new Cell(Integer.MAX_VALUE,Integer.MAX_VALUE);

        for (int z=0; z<land.side; z++) {
            if (b.contains(new Cell(0,z)) ||
                b.contains(new Cell(z,0)) ||
                b.contains(new Cell(land.side-1,z)) ||
                b.contains(new Cell(z,land.side-1))) {
                return output;
            }

            if (land.unoccupied(0,z)) {
                queue.add(new Cell(0,z,source));
            }

            if (land.unoccupied(z,0)) {
                queue.add(new Cell(z,0,source));
            }

            if (land.unoccupied(z,land.side-1)) {
                queue.add(new Cell(z,land.side-1,source));
            }

            if (land.unoccupied(land.side-1,z)) {
                queue.add(new Cell(land.side-1,z,source));
            }
        }

        // add cells adjacent to current road cells
        for (Cell p : road_cells) {
            for (Cell q : p.neighbors()) {
                if (!road_cells.contains(q) && land.unoccupied(q) && !b.contains(q)) {
                    queue.add(new Cell(q.i,q.j,p));
                }
            }
        }

        while (!queue.isEmpty()) {
            Cell p = queue.remove();
            checked[p.i][p.j] = true;

            for (Cell x : p.neighbors()) {
                if (b.contains(x)) { // trace back through search tree to find path
                    Cell tail = p;
                    while (!b.contains(tail) && !road_cells.contains(tail) &&
                           !tail.equals(source)) {
                        output.add(new Cell(tail.i,tail.j));
                        tail = tail.previous;
                    }

                    if (!output.isEmpty()) {
                        return output;
                    }
                } else if (!checked[x.i][x.j] && land.unoccupied(x.i,x.j)) {
                    x.previous = p;
                    queue.add(x);
                } 
            }
        }

        if (output.isEmpty() && queue.isEmpty())
            return null;
        else
            return output;
    }

    private Set<Cell> randomWalk(Set<Cell> b, Set<Cell> marked, Land land, int n) {
        ArrayList<Cell> adjCells = new ArrayList<Cell>();
        Set<Cell> output = new HashSet<Cell>();
        for (Cell p : b) {
            for (Cell q : p.neighbors()) {
                if (land.isField(q) || land.isPond(q)) {
                    return new HashSet<Cell>();
                }

                if (!b.contains(q) && !marked.contains(q) && land.unoccupied(q)) {
                    adjCells.add(q);
                }
            }
        }

        if (adjCells.isEmpty()) {
            return new HashSet<Cell>();
        }

        Cell tail = adjCells.get(gen.nextInt(adjCells.size()));
        for (int ii=0; ii<n; ii++) {
            ArrayList<Cell> walk_cells = new ArrayList<Cell>();
            for (Cell p : tail.neighbors()) {
                if (!b.contains(p) && !marked.contains(p) && land.unoccupied(p) &&
                    !output.contains(p)) {
                    walk_cells.add(p);
                }
            }

            if (walk_cells.isEmpty()) {
                return new HashSet<Cell>();
            }

            output.add(tail);	    
            tail = walk_cells.get(gen.nextInt(walk_cells.size()));
        }
        return output;
    }


}

