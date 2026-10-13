package pl.ultimo.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.model.PlayerHand;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MoveValidator Domain Unit Tests")
class MoveValidatorTest {

    @Test
    @DisplayName("Card matching active color should be valid")
    void matchingColorShouldBeValid() {
        Card top = new Card(CardColor.RED, CardValue.FIVE);
        Card played = new Card(CardColor.RED, CardValue.EIGHT);

        assertTrue(MoveValidator.isValidPlay(played, top, CardColor.RED));
    }

    @Test
    @DisplayName("Card matching top card value should be valid even if color differs")
    void matchingValueShouldBeValid() {
        Card top = new Card(CardColor.RED, CardValue.FIVE);
        Card played = new Card(CardColor.BLUE, CardValue.FIVE);

        assertTrue(MoveValidator.isValidPlay(played, top, CardColor.RED));
    }

    @Test
    @DisplayName("Action cards with matching symbol should be valid across colors")
    void matchingActionSymbolShouldBeValid() {
        Card top = new Card(CardColor.GREEN, CardValue.SKIP);
        Card played = new Card(CardColor.YELLOW, CardValue.SKIP);

        assertTrue(MoveValidator.isValidPlay(played, top, CardColor.GREEN));
    }

    @Test
    @DisplayName("Wild cards should always be valid plays on any top card")
    void wildCardsShouldAlwaysBeValid() {
        Card top = new Card(CardColor.RED, CardValue.FIVE);
        Card wild = new Card(CardColor.WILD, CardValue.WILD);
        Card wildDrawFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);

        assertTrue(MoveValidator.isValidPlay(wild, top, CardColor.RED));
        assertTrue(MoveValidator.isValidPlay(wildDrawFour, top, CardColor.RED));
    }

    @Test
    @DisplayName("Card with different color and different value should be rejected")
    void mismatchShouldBeInvalid() {
        Card top = new Card(CardColor.RED, CardValue.FIVE);
        Card played = new Card(CardColor.BLUE, CardValue.SEVEN);

        assertFalse(MoveValidator.isValidPlay(played, top, CardColor.RED));
    }

    @Test
    @DisplayName("Player cannot play out of their turn")
    void outOfTurnShouldFailValidation() {
        Player alice = new Player("1", "Alice");
        Player bob = new Player("2", "Bob");

        Card card = new Card(CardColor.RED, CardValue.FIVE);
        alice.getHand().addCard(card);

        // Attempt by Alice when Bob is active player
        Optional<String> error = MoveValidator.validateMove(alice, bob, card, card, CardColor.RED, null);
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("not your turn"));
    }

    @Test
    @DisplayName("Player cannot play a card they do not hold in hand")
    void cardNotInHandShouldFailValidation() {
        Player alice = new Player("1", "Alice");
        Card heldCard = new Card(CardColor.RED, CardValue.TWO);
        Card unheldCard = new Card(CardColor.RED, CardValue.FIVE);
        alice.getHand().addCard(heldCard);

        Optional<String> error = MoveValidator.validateMove(alice, alice, unheldCard, heldCard, CardColor.RED, null);
        assertTrue(error.isPresent());
        assertTrue(error.get().contains("do not hold card"));
    }

    @Test
    @DisplayName("Playing a Wild card requires specifying a valid standard color")
    void wildCardRequiresStandardColor() {
        Player alice = new Player("1", "Alice");
        Card wild = new Card(CardColor.WILD, CardValue.WILD);
        Card top = new Card(CardColor.RED, CardValue.TWO);
        alice.getHand().addCard(wild);

        // Null declared color
        Optional<String> errorNull = MoveValidator.validateMove(alice, alice, wild, top, CardColor.RED, null);
        assertTrue(errorNull.isPresent());

        // Declaring WILD color itself is illegal
        Optional<String> errorWild = MoveValidator.validateMove(alice, alice, wild, top, CardColor.RED, CardColor.WILD);
        assertTrue(errorWild.isPresent());

        // Declaring BLUE is valid
        Optional<String> valid = MoveValidator.validateMove(alice, alice, wild, top, CardColor.RED, CardColor.BLUE);
        assertTrue(valid.isEmpty());
    }

    @Test
    @DisplayName("hasPlayableCard correctly identifies if hand has matching cards")
    void hasPlayableCardIdentifiesValidCards() {
        PlayerHand hand = new PlayerHand();
        hand.addCard(new Card(CardColor.GREEN, CardValue.NINE));
        hand.addCard(new Card(CardColor.BLUE, CardValue.ONE));

        Card topRedNine = new Card(CardColor.RED, CardValue.NINE);
        Card topYellowThree = new Card(CardColor.YELLOW, CardValue.THREE);

        // Green 9 matches value 9
        assertTrue(MoveValidator.hasPlayableCard(hand, topRedNine, CardColor.RED));

        // Neither matches Yellow 3
        assertFalse(MoveValidator.hasPlayableCard(hand, topYellowThree, CardColor.YELLOW));
    }
}
