package io.swagger.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.openapitools.jackson.nullable.JsonNullable;
import io.swagger.configuration.NotUndefined;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import javax.validation.Valid;
import javax.validation.constraints.*;

/**
 * RestDices
 */
@Validated
@NotUndefined
@javax.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.SpringCodegen", date = "2025-04-17T15:57:45.288433650Z[GMT]")


public class RestDices   {
  @JsonProperty("dices")
  @Valid
  private List<Integer> dices = null;
  @JsonProperty("score")

  @JsonInclude(JsonInclude.Include.NON_ABSENT)  // Exclude from JSON if absent
  @JsonSetter(nulls = Nulls.FAIL)    // FAIL setting if the value is null
  private Integer score = null;


  public RestDices dices(List<Integer> dices) { 

    this.dices = dices;
    return this;
  }

  public RestDices addDicesItem(Integer dicesItem) {
    if (this.dices == null) {
      this.dices = new ArrayList<Integer>();
    }
    this.dices.add(dicesItem);
    return this;
  }

  /**
   * Get dices
   * @return dices
   **/
  
  @Schema(description = "")
  
  public List<Integer> getDices() {  
    return dices;
  }



  public void setDices(List<Integer> dices) { 
    this.dices = dices;
  }

  public RestDices score(Integer score) { 

    this.score = score;
    return this;
  }

  /**
   * Get score
   * @return score
   **/
  
  @Schema(description = "")
  
  public Integer getScore() {  
    return score;
  }



  public void setScore(Integer score) { 
    this.score = score;
  }

  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RestDices restDices = (RestDices) o;
    return Objects.equals(this.dices, restDices.dices) &&
        Objects.equals(this.score, restDices.score);
  }

  @Override
  public int hashCode() {
    return Objects.hash(dices, score);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RestDices {\n");
    
    sb.append("    dices: ").append(toIndentedString(dices)).append("\n");
    sb.append("    score: ").append(toIndentedString(score)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}
